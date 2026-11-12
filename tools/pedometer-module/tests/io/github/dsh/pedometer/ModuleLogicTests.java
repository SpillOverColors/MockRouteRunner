package io.github.dsh.pedometer;

import java.util.List;
import java.util.Locale;

/** Offline tests for generation cancellation, actual active time and mapping identity. */
public final class ModuleLogicTests {
    private static int passed, failed;
    private static final StringBuilder LOG = new StringBuilder();

    public static void main(String[] args) throws Exception {
        try {
            testGenerationCancellation();
            testActualActiveTime();
            testCounterFloor();
            testRegistrationIdentity();
            testResumePauseProtocol();
        } catch (Throwable t) {
            failed++;
            LOG.append("FAIL harness :: ").append(t).append('\n');
            t.printStackTrace();
        }
        String summary = String.format(Locale.US, "MODULE TESTS: %d passed, %d failed%n", passed, failed);
        System.out.print(LOG);
        System.out.print(summary);
        if (args.length > 0) {
            java.nio.file.Files.write(java.nio.file.Paths.get(args[0]),
                    (LOG.toString() + summary).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (failed > 0) System.exit(1);
    }

    private static void testGenerationCancellation() {
        CadenceEngine e = new CadenceEngine();
        e.start(100, 130, 42, 1_000_000_000L, 500);
        CadenceEngine.Tick t = e.tick(1_100_000_000L);
        check("first tick exists", t != null);
        check("tick generation active", e.deliveryActive(t.generation));
        e.pause();
        check("paused tick is null", e.tick(1_200_000_000L) == null);
        check("old generation cancelled on pause", !e.deliveryActive(t.generation));
        e.resume(1_300_000_000L);
        check("pre-pause generation still cancelled", !e.deliveryActive(t.generation));
        CadenceEngine.Tick r = e.tick(1_400_000_000L);
        check("resume tick exists", r != null);
        check("resume generation active", r != null && e.deliveryActive(r.generation));
        e.stop();
        check("stop cancels queued generation", r != null && !e.deliveryActive(r.generation));
    }

    private static void testActualActiveTime() {
        CadenceEngine e = new CadenceEngine();
        e.start(100, 130, 7, 0L, 0);
        e.tick(1_250_000_000L); // actual 1.25 s, not the planned ~0.6 s interval
        check(String.format(Locale.US, "motionSeconds actual %.3f", e.motionSeconds()),
                Math.abs(e.motionSeconds() - 1.25) < 1e-6);
        e.pause();
        e.resume(10_000_000_000L);
        e.tick(11_000_000_000L);
        check(String.format(Locale.US, "motion after pause-resume %.3f", e.motionSeconds()),
                Math.abs(e.motionSeconds() - 2.25) < 1e-6);
    }

    private static void testCounterFloor() {
        CadenceEngine e = new CadenceEngine();
        e.noteReal(100);
        e.start(100, 130, 1, 0L, 100);
        CadenceEngine.Tick t = e.tick(600_000_000L);
        check("counter starts at real baseline+1", t != null && t.counterValue == 101);
        e.stop();
        e.start(100, 130, 1, 2_000_000_000L, 100);
        CadenceEngine.Tick t2 = e.tick(2_600_000_000L);
        check("restart does not go below synthetic floor",
                t2 != null && t2.counterValue >= 101);
    }

    private static void testResumePauseProtocol() {
        for (double[] bad : new double[][]{{0, 130}, {1, 301}, {200, 100},
                {Double.NaN, 130}, {1, Double.POSITIVE_INFINITY}}) {
            try {
                new CadenceEngine().start(bad[0], bad[1], 1, 0L, 0);
                failed++;
                LOG.append("FAIL cadence engine rejects ").append(bad[0]).append('/').append(bad[1]).append('\n');
            } catch (IllegalArgumentException e) {
                passed++;
                LOG.append("PASS cadence engine rejects ").append(bad[0]).append('/').append(bad[1]).append('\n');
            }
        }
        new CadenceEngine().start(1, 300, 1, 0L, 0);
        passed++;
        LOG.append("PASS cadence engine accepts 1/300\n");
        CadenceEngine e = new CadenceEngine();
        check("resume before START is NOT_STARTED",
                e.resume(1L) == CadenceEngine.ResumeResult.NOT_STARTED);
        check("NOT_STARTED reply permits fresh START",
                ControlProtocol.needsFreshStart(ControlProtocol.resumeReply(
                        CadenceEngine.ResumeResult.NOT_STARTED, 0)));
        check("NOT_STARTED reply is not a RUNNING ACK",
                !ControlProtocol.isResumeOk(ControlProtocol.resumeReply(
                        CadenceEngine.ResumeResult.NOT_STARTED, 0)));
        e.start(100, 130, 1, 2L, 0);
        check("resume while running is ALREADY_RUNNING",
                e.resume(3L) == CadenceEngine.ResumeResult.ALREADY_RUNNING);
        check("pause first", e.pause() == CadenceEngine.PauseResult.PAUSED);
        check("pause again is ALREADY_PAUSED",
                e.pause() == CadenceEngine.PauseResult.ALREADY_PAUSED);
        check("resume paused is RESUMED", e.resume(5L) == CadenceEngine.ResumeResult.RESUMED);
        check("RUNNING ACK recognized", ControlProtocol.isResumeOk(
                ControlProtocol.resumeReply(CadenceEngine.ResumeResult.RESUMED, 10)));
        e.stop();
        check("resume after STOP is NOT_STARTED",
                e.resume(6L) == CadenceEngine.ResumeResult.NOT_STARTED);
        check("pause after STOP is NOT_STARTED",
                e.pause() == CadenceEngine.PauseResult.NOT_STARTED);

        DeliveryGate gate = new DeliveryGate();
        long token = gate.token();
        check("active gate allows queued token", gate.allows(token));
        gate.deactivate();
        check("unregister invalidates queued token", !gate.allows(token));
        check("deactivated gate issues no new token", gate.token() == 0);
        DeliveryGate other = new DeliveryGate();
        check("other registration gate unaffected", other.allows(other.token()));
    }

    private static void testRegistrationIdentity() {
        RegistrationTable<String> t = new RegistrationTable<>();
        Object m1 = new Object(), m2 = new Object(), listener = new Object();
        Object counter = new Object(), detector = new Object();
        t.put(m1, listener, counter, "counter");
        t.put(m1, listener, detector, "detector");
        t.put(m2, listener, counter, "other-manager");
        check("same listener two sensors", t.find(m1, listener, counter).equals("counter")
                && t.find(m1, listener, detector).equals("detector"));
        check("same sensor under two managers", t.find(m2, listener, counter).equals("other-manager"));
        List<String> one = t.remove(m1, listener, counter);
        check("remove one sensor only", one.size() == 1 && "counter".equals(one.get(0)));
        check("detector remains", t.find(m1, listener, detector) != null);
        check("other manager remains", t.find(m2, listener, counter) != null);
        List<String> all = t.remove(m1, listener, null);
        check("remove listener all sensors", all.size() == 1 && "detector".equals(all.get(0)));
        check("other manager not removed", t.find(m2, listener, counter) != null);
        check("size after removals", t.size() == 1);
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; LOG.append("PASS ").append(name).append('\n'); }
        else { failed++; LOG.append("FAIL ").append(name).append('\n'); }
    }
}
