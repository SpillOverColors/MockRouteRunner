package io.github.dsh.mockroute;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Best-effort loopback bridge to an LSPatch-patched target process.
 *
 * START/STOP are serialized on one worker thread, so a STOP can never overtake
 * a START. A connection is "ready" only after AUTH + target HELLO + START ACK;
 * a single failure leaves an explicit not-ready state and is not retried in a
 * loop. The session key is a coordination token, not strong authentication.
 */
final class PedometerBridge {
    private static final String SESSION_KEY = "dsh-pedometer-v1";
    private static final int CONNECT_TIMEOUT_MS = 700;
    private static final int READ_TIMEOUT_MS = 1200;
    private static final int MAX_LINE = 256;
    private static final AtomicLong SEQ = new AtomicLong();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "pedometer-bridge");
            t.setDaemon(true);
            return t;
        }
    });

    private static volatile boolean ready;
    private static volatile String state = "未连接";
    private static volatile String target = "";

    private PedometerBridge() {}

    static int portFor(String packageName) {
        int h = 0x811c9dc5;
        String p = packageName == null ? "" : packageName;
        for (int i = 0; i < p.length(); i++) {
            h ^= p.charAt(i);
            h *= 0x01000193;
        }
        return 39000 + Math.floorMod(h, 1000);
    }

    static boolean isReady() { return ready; }
    static String status() { return state; }

    static void start(final String targetPkg, final double min, final double max, final long seed) {
        final String pkg = targetPkg == null ? "" : targetPkg.trim();
        if (!validPackage(pkg)) {
            ready = false;
            state = "未配置有效目标包名";
            RunState.append("步频增强：未配置有效目标包名，跳过");
            return;
        }
        target = pkg;
        ready = false;
        state = "连接中：" + pkg;
        final long seq = SEQ.incrementAndGet();
        WORKER.execute(new Runnable() {
            @Override public void run() { doStart(seq, pkg, min, max, seed); }
        });
    }

    static void pause() {
        final String pkg = target;
        if (!validPackage(pkg)) return;
        ready = false;
        state = "暂停中：" + pkg;
        final long seq = SEQ.incrementAndGet();
        WORKER.execute(new Runnable() {
            @Override public void run() { doSimple(seq, pkg, "PAUSE", "已暂停"); }
        });
    }

    static void resume(final double min, final double max, final long seed) {
        final String pkg = target;
        if (!validPackage(pkg)) return;
        ready = false;
        state = "恢复中：" + pkg;
        final long seq = SEQ.incrementAndGet();
        WORKER.execute(new Runnable() {
            @Override public void run() { doResume(seq, pkg, min, max, seed); }
        });
    }

    static void stop() {
        final String pkg = target;
        if (!validPackage(pkg)) return;
        ready = false;
        state = "停止中：" + pkg;
        final long seq = SEQ.incrementAndGet();
        WORKER.execute(new Runnable() {
            @Override public void run() { doStop(seq, pkg); }
        });
    }

    private static void doStart(long seq, String pkg, double min, double max, long seed) {
        try {
            Socket s = open(pkg);
            try {
                PrintWriter out = writer(s);
                InputStream in = s.getInputStream();
                authHello(out, in, pkg);
                if (seq != SEQ.get()) return;
                out.println("START " + pkg + " " + min + " " + max + " " + seed);
                String reply = readBoundedLine(in);
                if (!BridgeProtocol.startOk(reply, pkg)) {
                    throw new IOException(reply == null ? "START 无响应" : reply);
                }
                ready = true;
                state = "就绪：" + pkg + ":" + portFor(pkg);
                RunState.pedometerStatus = "就绪 " + pkg + ":" + portFor(pkg);
                RunState.append("步频增强已就绪：" + pkg + "（" + reply + "）");
            } finally {
                close(s);
            }
        } catch (Exception e) {
            ready = false;
            state = "未就绪：" + safe(e.getMessage());
            RunState.pedometerStatus = "未就绪：" + safe(e.getMessage());
            RunState.append("步频增强桥接失败（目标未运行/未改包）：" + safe(e.getMessage()));
        }
    }

    private static void doResume(long seq, String pkg, double min, double max, long seed) {
        try {
            Socket s = open(pkg);
            try {
                PrintWriter out = writer(s);
                InputStream in = s.getInputStream();
                authHello(out, in, pkg);
                if (seq != SEQ.get()) return;
                out.println("RESUME");
                String reply = readBoundedLine(in);
                boolean freshStart = BridgeProtocol.resumeNeedsStart(reply);
                if (freshStart) {
                    // Only ERR NOT_STARTED means the target process has no engine.
                    // Auth/target/command errors must not be converted into START.
                    out.println("START " + pkg + " " + min + " " + max + " " + seed);
                    reply = readBoundedLine(in);
                }
                boolean ok = freshStart
                        ? BridgeProtocol.startOk(reply, pkg)
                        : BridgeProtocol.resumeOk(reply);
                if (!ok) {
                    throw new IOException(reply == null ? "RESUME 无响应" : reply);
                }
                ready = true;
                state = "就绪：" + pkg + ":" + portFor(pkg);
                RunState.pedometerStatus = "就绪 " + pkg + ":" + portFor(pkg);
                RunState.append("步频增强已恢复：" + pkg);
            } finally {
                close(s);
            }
        } catch (Exception e) {
            ready = false;
            state = "未就绪：" + safe(e.getMessage());
            RunState.pedometerStatus = state;
            RunState.append("步频增强恢复失败：" + safe(e.getMessage()));
        }
    }

    private static void doSimple(long seq, String pkg, String command, String okState) {
        try {
            Socket s = open(pkg);
            try {
                PrintWriter out = writer(s);
                InputStream in = s.getInputStream();
                authHello(out, in, pkg);
                if (seq != SEQ.get()) return;
                out.println(command);
                String reply = readBoundedLine(in);
                if (reply == null || reply.startsWith("ERR")) {
                    throw new IOException(reply == null ? command + " 无响应" : reply);
                }
                ready = false;
                state = okState + "：" + pkg;
                RunState.pedometerStatus = state;
            } finally {
                close(s);
            }
        } catch (Exception e) {
            ready = false;
            state = "未就绪：" + safe(e.getMessage());
            RunState.pedometerStatus = state;
        }
    }

    private static void doStop(long seq, String pkg) {
        try {
            Socket s = open(pkg);
            try {
                PrintWriter out = writer(s);
                InputStream in = s.getInputStream();
                authHello(out, in, pkg);
                out.println("STOP");
                String reply = readBoundedLine(in);
                if (reply == null || reply.startsWith("ERR")) {
                    throw new IOException(reply == null ? "STOP 无响应" : reply);
                }
                state = "已停止：" + pkg;
            } finally {
                close(s);
            }
        } catch (Exception e) {
            state = "停止失败：" + safe(e.getMessage());
        } finally {
            ready = false;
            if (seq == SEQ.get()) {
                RunState.pedometerStatus = state;
                target = "";
            }
        }
    }

    private static Socket open(String pkg) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", portFor(pkg)), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(READ_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            return s;
        } catch (IOException e) {
            close(s);
            throw e;
        }
    }

    private static PrintWriter writer(Socket s) throws IOException {
        return new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.US_ASCII), true);
    }

    private static void authHello(PrintWriter out, InputStream in, String pkg) throws IOException {
        out.println("AUTH " + SESSION_KEY);
        String auth = readBoundedLine(in);
        if (!"OK AUTH".equals(auth)) throw new IOException("控制通道拒绝会话");
        out.println("HELLO " + pkg);
        String hello = readBoundedLine(in);
        if (hello == null || !hello.startsWith("READY " + pkg + " ")) {
            throw new IOException(hello == null ? "无就绪握手" : hello);
        }
    }

    private static String readBoundedLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') break;
            if (c == '\r') continue;
            if (b.size() >= MAX_LINE) throw new IOException("控制响应过长");
            b.write(c);
        }
        if (!any && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static boolean validPackage(String p) {
        if (p == null || p.length() == 0 || p.length() > 200) return false;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (!(c == '.' || c == '_' || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }

    private static void close(Socket s) {
        try { s.close(); } catch (Throwable ignored) {}
    }

    private static String safe(String s) { return s == null ? "未知错误" : s; }
}
