package io.github.dsh.pedometer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;

/**
 * Real loopback wire test for the START/RESUME fallback contract. It uses the
 * same CadenceEngine + ControlProtocol reply producers and the same
 * "fallback only on ERR NOT_STARTED" client decision as PedometerBridge.
 */
public final class LoopbackProtocolTests {
    private static int passed, failed;
    private static final StringBuilder LOG = new StringBuilder();

    public static void main(String[] args) throws Exception {
        try {
            freshProcessFallsBackToStart();
            pausedProcessDoesNotStart();
        } catch (Throwable t) {
            failed++;
            LOG.append("FAIL loopback harness :: ").append(t).append('\n');
            t.printStackTrace();
        }
        String summary = String.format(Locale.US, "LOOPBACK TESTS: %d passed, %d failed%n", passed, failed);
        System.out.print(LOG);
        System.out.print(summary);
        if (args.length > 0) {
            java.nio.file.Files.write(java.nio.file.Paths.get(args[0]),
                    (LOG.toString() + summary).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (failed > 0) System.exit(1);
    }

    private static void freshProcessFallsBackToStart() throws Exception {
        CadenceEngine engine = new CadenceEngine();
        Server srv = new Server(engine, false);
        srv.start();
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", srv.port), 1000);
            s.setSoTimeout(1500);
            PrintWriter out = new PrintWriter(new OutputStreamWriter(
                    s.getOutputStream(), java.nio.charset.StandardCharsets.US_ASCII), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    s.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
            out.println("AUTH dsh-pedometer-v1");
            check("fresh auth", "OK AUTH".equals(in.readLine()));
            out.println("HELLO com.test.app");
            check("fresh hello", in.readLine().startsWith("READY com.test.app "));
            out.println("RESUME");
            String resume = in.readLine();
            check("fresh RESUME is ERR NOT_STARTED", "ERR NOT_STARTED".equals(resume));
            check("bridge falls back only here", ControlProtocol.needsFreshStart(resume));
            out.println("START com.test.app 100 130 7");
            String start = in.readLine();
            check("fresh fallback START accepted", start.startsWith("OK START com.test.app "));
            check("engine now running", engine.isRunning());
        } finally {
            srv.close();
        }
    }

    private static void pausedProcessDoesNotStart() throws Exception {
        CadenceEngine engine = new CadenceEngine();
        engine.start(100, 130, 7, 1_000_000_000L, 0);
        engine.pause();
        Server srv = new Server(engine, false);
        srv.start();
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", srv.port), 1000);
            s.setSoTimeout(1500);
            PrintWriter out = new PrintWriter(new OutputStreamWriter(
                    s.getOutputStream(), java.nio.charset.StandardCharsets.US_ASCII), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    s.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
            out.println("AUTH dsh-pedometer-v1");
            in.readLine();
            out.println("HELLO com.test.app");
            in.readLine();
            out.println("RESUME");
            String resume = in.readLine();
            check("paused RESUME is OK RUNNING", ControlProtocol.isResumeOk(resume));
            check("paused RESUME does not request fresh START",
                    !ControlProtocol.needsFreshStart(resume));
            check("engine resumed", engine.isRunning() && !engine.isPaused());
        } finally {
            srv.close();
        }
    }

    private static final class Server {
        final CadenceEngine engine;
        final boolean startFirst;
        final ServerSocket server;
        final int port;
        final Thread thread;

        Server(CadenceEngine engine, boolean startFirst) throws Exception {
            this.engine = engine;
            this.startFirst = startFirst;
            this.server = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
            this.port = server.getLocalPort();
            this.thread = new Thread(new Runnable() {
                @Override public void run() { serve(); }
            }, "loopback-test-server");
            thread.setDaemon(true);
        }

        void start() { thread.start(); }

        void serve() {
            try (Socket s = server.accept()) {
                s.setSoTimeout(1500);
                BufferedReader in = new BufferedReader(new InputStreamReader(
                        s.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
                PrintWriter out = new PrintWriter(new OutputStreamWriter(
                        s.getOutputStream(), java.nio.charset.StandardCharsets.US_ASCII), true);
                String line;
                while ((line = in.readLine()) != null) {
                    String[] p = line.split("\\s+");
                    if ("AUTH".equals(p[0])) {
                        out.println("OK AUTH");
                    } else if ("HELLO".equals(p[0])) {
                        out.println("READY com.test.app " + port);
                    } else if ("RESUME".equals(p[0])) {
                        out.println(ControlProtocol.resumeReply(
                                engine.resume(System.nanoTime()), engine.generation()));
                    } else if ("START".equals(p[0])) {
                        engine.start(100, 130, 7L, System.nanoTime(), engine.lastRealCounter());
                        out.println("OK START com.test.app 100.0 130.0 7 baseline=" + engine.baseline());
                        break;
                    } else {
                        out.println("ERR command");
                    }
                }
            } catch (Throwable t) {
                failed++;
                LOG.append("FAIL loopback server :: ").append(t).append('\n');
            }
        }

        void close() {
            try { server.close(); } catch (Throwable ignored) {}
            try { thread.join(1000); } catch (Throwable ignored) {}
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; LOG.append("PASS ").append(name).append('\n'); }
        else { failed++; LOG.append("FAIL ").append(name).append('\n'); }
    }
}
