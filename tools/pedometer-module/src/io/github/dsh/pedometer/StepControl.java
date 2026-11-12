package io.github.dsh.pedometer;

import android.hardware.Sensor;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;

/**
 * Process-local cadence source. Default is passive/pass-through; START is required.
 *
 * Control is bounded and target-isolated: loopback only, one port derived from
 * the patched package, at most two live connections, short socket timeouts,
 * bounded lines/commands, and START requires the current process package.
 * The static session key is NOT a security boundary; any local process that
 * knows the package can attempt the same handshake. It only prevents accidental
 * cross-app control, not a hostile local app.
 */
final class StepControl {
    private static final Object LOCK = new Object();
    private static final List<StepProxy> PROXIES = new ArrayList<>();
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final CadenceEngine ENGINE = new CadenceEngine();
    private static final String SESSION_KEY = "dsh-pedometer-v1";
    private static final int MAX_CONNECTIONS = 2;
    private static final int SOCKET_TIMEOUT_MS = 1500;
    private static final int MAX_LINE = 192;
    private static final int MAX_COMMANDS = 12;
    private static final Semaphore CONNECTION_SLOTS = new Semaphore(MAX_CONNECTIONS);

    private static boolean serverStarted = false;
    private static String target = "";

    private StepControl() {}

    static void configureTarget(String packageName) {
        synchronized (LOCK) { target = packageName == null ? "" : packageName; }
    }

    static boolean isSuppressingReal() {
        synchronized (LOCK) { return ENGINE.isRunning(); }
    }

    static boolean deliveryActive(long generation) {
        synchronized (LOCK) { return ENGINE.deliveryActive(generation); }
    }

    static void register(StepProxy p) {
        synchronized (LOCK) {
            PROXIES.add(p);
            startServerLocked();
        }
        // Deliberately no schedule: default is pass-through until START.
    }

    static void unregister(StepProxy p) {
        if (p != null) p.deactivate();
        synchronized (LOCK) { PROXIES.remove(p); }
    }

    static void noteReal(int type, float value) {
        if (type == Sensor.TYPE_STEP_COUNTER && Float.isFinite(value)) {
            synchronized (LOCK) { ENGINE.noteReal((long) value); }
        }
    }

    static void ensureServer() { synchronized (LOCK) { startServerLocked(); } }

    /** Deterministic package-derived port avoids one fixed port shared by all patched apps. */
    static int portFor(String packageName) {
        int h = 0x811c9dc5;
        String p = packageName == null ? "" : packageName;
        for (int i = 0; i < p.length(); i++) {
            h ^= p.charAt(i);
            h *= 0x01000193;
        }
        return 39000 + Math.floorMod(h, 1000);
    }

    private static boolean authorized(String token) { return SESSION_KEY.equals(token); }

    private static boolean validPackage(String p) {
        if (p == null || p.length() == 0 || p.length() > 200) return false;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (!(c == '.' || c == '_' || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }

    private static void startServerLocked() {
        if (serverStarted || !validPackage(target)) return;
        serverStarted = true;
        final String boundTarget = target;
        final int port = portFor(boundTarget);
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                ServerSocket ss = null;
                try {
                    ss = new ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"));
                    android.util.Log.i("PedometerAdapter", "control listening on loopback:" + port + " target=" + boundTarget);
                    while (true) {
                        final Socket s = ss.accept();
                        if (!CONNECTION_SLOTS.tryAcquire()) {
                            try { s.close(); } catch (Throwable ignored) {}
                            continue;
                        }
                        try {
                            s.setSoTimeout(SOCKET_TIMEOUT_MS);
                            s.setTcpNoDelay(true);
                        } catch (Throwable ignored) {}
                        Thread c = new Thread(new Runnable() {
                            @Override public void run() {
                                try { handle(s); }
                                finally {
                                    try { s.close(); } catch (Throwable ignored) {}
                                    CONNECTION_SLOTS.release();
                                }
                            }
                        }, "pedometer-control");
                        c.setDaemon(true);
                        c.start();
                    }
                } catch (Throwable ex) {
                    android.util.Log.e("PedometerAdapter", "control server unavailable on " + port, ex);
                    synchronized (LOCK) { serverStarted = false; }
                } finally {
                    if (ss != null) try { ss.close(); } catch (Throwable ignored) {}
                }
            }
        }, "pedometer-server");
        t.setDaemon(true);
        t.start();
    }

    private static String readBoundedLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') break;
            if (c == '\r') continue;
            if (b.size() >= MAX_LINE) throw new IOException("control line too long");
            b.write(c);
        }
        if (!any && b.size() == 0) return null;
        return new String(b.toByteArray(), java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static void handle(Socket s) {
        try {
            InputStream in = s.getInputStream();
            PrintWriter out = new PrintWriter(new OutputStreamWriter(
                    s.getOutputStream(), java.nio.charset.StandardCharsets.US_ASCII), true);
            boolean authed = false;
            int commands = 0;
            int authFailures = 0;
            String line;
            while (commands++ < MAX_COMMANDS && (line = readBoundedLine(in)) != null) {
                String[] p = line.trim().split("\\s+");
                if (p.length == 0) continue;
                String cmd = p[0].toUpperCase(Locale.US);
                if ("AUTH".equals(cmd)) {
                    authed = p.length == 2 && authorized(p[1]);
                    if (!authed) authFailures++;
                    out.println(authed ? "OK AUTH" : "ERR UNAUTHORIZED");
                    if (authFailures >= 3) break;
                    continue;
                }
                if (!authed) { out.println("ERR UNAUTHORIZED"); continue; }
                if ("HELLO".equals(cmd)) {
                    if (p.length != 2 || !validPackage(p[1]) || !p[1].equals(target)) {
                        out.println("ERR WRONG_TARGET");
                    } else {
                        out.println("READY " + target + " " + portFor(target));
                    }
                } else if ("START".equals(cmd)) {
                    if (p.length < 5) { out.println("ERR START <package> <min> <max> <seed>"); continue; }
                    String pkg = p[1];
                    double lo, hi;
                    long sd;
                    try {
                        lo = Double.parseDouble(p[2]);
                        hi = Double.parseDouble(p[3]);
                        sd = Long.parseLong(p[4]);
                    } catch (Throwable ex) { out.println("ERR BAD_PARAM"); continue; }
                    if (!Double.isFinite(lo) || !Double.isFinite(hi) || lo < 1 || hi > 300 || lo > hi) {
                        out.println("ERR BAD_RANGE"); continue;
                    }
                    long baseline;
                    synchronized (LOCK) {
                        if (!pkg.equals(target)) { out.println("ERR WRONG_TARGET"); continue; }
                        ENGINE.start(lo, hi, sd, SystemClock.elapsedRealtimeNanos(), ENGINE.lastRealCounter());
                        baseline = ENGINE.baseline();
                        scheduleNextLocked();
                    }
                    out.println("OK START " + pkg + " " + lo + " " + hi + " " + sd + " baseline=" + baseline);
                } else if ("PAUSE".equals(cmd)) {
                    CadenceEngine.PauseResult result;
                    long gen;
                    synchronized (LOCK) {
                        result = ENGINE.pause();
                        gen = ENGINE.generation();
                    }
                    if (result != CadenceEngine.PauseResult.NOT_STARTED) {
                        HANDLER.removeCallbacks(TICK);
                    }
                    out.println(ControlProtocol.pauseReply(result, gen));
                } else if ("RESUME".equals(cmd)) {
                    CadenceEngine.ResumeResult result;
                    long gen;
                    synchronized (LOCK) {
                        result = ENGINE.resume(SystemClock.elapsedRealtimeNanos());
                        gen = ENGINE.generation();
                        if (result == CadenceEngine.ResumeResult.RESUMED) scheduleNextLocked();
                    }
                    out.println(ControlProtocol.resumeReply(result, gen));
                } else if ("STOP".equals(cmd)) {
                    synchronized (LOCK) { ENGINE.stop(); }
                    HANDLER.removeCallbacks(TICK);
                    out.println("OK STOPPED");
                } else if ("STATUS".equals(cmd)) {
                    out.println(status());
                } else {
                    out.println("ERR command");
                }
            }
        } catch (Throwable ex) {
            android.util.Log.e("PedometerAdapter", "control connection", ex);
        }
    }

    static String status() {
        synchronized (LOCK) {
            return "STATUS target=" + target + " port=" + portFor(target)
                    + " running=" + ENGINE.isRunning() + " paused=" + ENGINE.isPaused()
                    + " steps=" + ENGINE.steps() + " baseline=" + ENGINE.baseline()
                    + " cadence=" + String.format(Locale.US, "%.1f", ENGINE.cadence());
        }
    }

    private static void scheduleNextLocked() {
        HANDLER.removeCallbacks(TICK);
        HANDLER.postDelayed(TICK, ENGINE.nextIntervalMs());
    }

    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            long generation;
            int value;
            long timestamp;
            List<StepProxy> copy;
            synchronized (LOCK) {
                CadenceEngine.Tick tick = ENGINE.tick(SystemClock.elapsedRealtimeNanos());
                if (tick == null) return;
                generation = tick.generation;
                value = tick.counterValue;
                timestamp = tick.timestampNanos;
                copy = new ArrayList<>(PROXIES);
                HANDLER.postDelayed(TICK, ENGINE.nextIntervalMs());
            }
            for (StepProxy p : copy) {
                if (p.sensor().getType() == Sensor.TYPE_STEP_COUNTER) {
                    p.inject(value, timestamp, generation);
                } else {
                    p.inject(1, timestamp, generation);
                }
            }
        }
    };
}
