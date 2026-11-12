import io.github.dsh.mockroute.MotionSimulator;
import io.github.dsh.mockroute.Outcome;
import io.github.dsh.mockroute.RouteModel;

import java.util.Locale;

/** Pure-JVM offline tests; no Android device, no adb, no third-party library. */
public final class CoreTests {
    private static int passed, failed;
    private static final StringBuilder LOG = new StringBuilder();

    public static void main(String[] args) throws Exception {
        String logPath = args.length > 0 ? args[0] : null;
        try {
            testGpxSingleLoop();
            testJsonTwoSharedLoops();
            testOpenPolyline();
            testRejectMultipleOpen();
            testOpenMotionReversal();
            testOpenScheduledReversal();
            testOpenReversalSpeedAudit();
            testOpenBreakpointSnapshot();
            testRejectDisconnected();
            testRejectBadCoordinates();
            testMotionSpeedAndContinuity();
            testJitterSpeed();
            testNarrowPaceUpperBound();
            testTargetCrossingJunction();
            testTargetFinalCrossingRealistic();
            testFinalTickSpeed();
            testJunctionSwitching();
            testCadenceAndPaceRange();
            testBreakpointSnapshot();
            testBreakpointMidTransition();
            testCleanupStatus();
            testContentDetection();
            testExampleFiles();
        } catch (Throwable t) {
            fail("test harness", t.toString());
            t.printStackTrace();
        }
        String summary = String.format(Locale.US, "TESTS: %d passed, %d failed%n", passed, failed);
        System.out.print(LOG);
        System.out.print(summary);
        if (logPath != null) {
            java.nio.file.Files.write(java.nio.file.Paths.get(logPath),
                    (LOG.toString() + summary).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (failed > 0) System.exit(1);
    }

    private static void testGpxSingleLoop() {
        String gpx = "<?xml version='1.0'?><gpx xmlns='http://www.topografix.com/GPX/1/1'>"
                + "<trk><name>SYNTHETIC TEST LOOP - NOT CAMPUS</name><trkseg>"
                + "<trkpt lat='0.0000000' lon='0.0000000'/><trkpt lat='0.0000000' lon='0.0010000'/>"
                + "<trkpt lat='0.0010000' lon='0.0010000'/><trkpt lat='0.0010000' lon='0.0000000'/>"
                + "<trkpt lat='0.0000000' lon='0.0000000'/></trkseg></trk></gpx>";
        try {
            RouteModel.RouteSet rs = RouteModel.parseGpx(gpx, "synthetic.gpx");
            check("gpx loop count", rs.loops.size() == 1);
            check("gpx loop length ~444m", rs.loops.get(0).length > 400 && rs.loops.get(0).length < 480);
            check("gpx junction 0,0", rs.junction.lat == 0 && rs.junction.lon == 0);
            String json = RouteModel.toJson(rs);
            RouteModel.RouteSet again = RouteModel.parseJson(json, "roundtrip.json");
            check("json roundtrip loop count", again.loops.size() == 1);
            check("json roundtrip length", Math.abs(again.loops.get(0).length - rs.loops.get(0).length) < 0.01);
        } catch (Exception e) { fail("gpx single loop", e.toString()); }
    }

    private static void testJsonTwoSharedLoops() {
        String json = "{\"routes\":["
                + "{\"name\":\"A\",\"points\":[[0,0],[0,0.001],[0.001,0.001],[0.001,0],[0,0]]},"
                + "{\"name\":\"B\",\"points\":[[0,0],[0,-0.001],[-0.001,-0.001],[-0.001,0],[0,0]]}]}";
        try {
            RouteModel.RouteSet rs = RouteModel.parseJson(json, "two.json");
            check("json two loops", rs.loops.size() == 2);
            check("json names", "A".equals(rs.loops.get(0).name) && "B".equals(rs.loops.get(1).name));
        } catch (Exception e) { fail("json two loops", e.toString()); }
    }

    private static void testOpenPolyline() {
        String json = "{\"name\":\"open\",\"closed\":false,\"points\":[[0,0],[0,0.002],[0.001,0.003]]}";
        try {
            RouteModel.RouteSet rs = RouteModel.parseJson(json, "open.json");
            check("open single route", rs.isOpenSingle() && rs.loops.size() == 1);
            check("open loop flag", !rs.loops.get(0).closed);
            check("open has no shared junction", !rs.hasJunction());
            check("open length", rs.loops.get(0).length > 200);
            String out = RouteModel.toJson(rs);
            RouteModel.RouteSet again = RouteModel.parseJson(out, "open-roundtrip.json");
            check("open JSON roundtrip", again.isOpenSingle() && !again.loops.get(0).closed);
            check("open JSON omits shared junction", !out.contains("\"junction\""));
        } catch (Exception e) { fail("open polyline", e.toString()); }
        String gpx = "<?xml version='1.0'?><gpx xmlns='http://www.topografix.com/GPX/1/1'>"
                + "<trk><name>open-gpx</name><trkseg>"
                + "<trkpt lat='0' lon='0'/><trkpt lat='0' lon='0.002'/><trkpt lat='0.001' lon='0.003'/>"
                + "</trkseg></trk></gpx>";
        try {
            RouteModel.RouteSet rs = RouteModel.parseGpx(gpx, "open.gpx");
            check("open GPX single route", rs.isOpenSingle() && rs.loops.get(0).points.size() == 3);
        } catch (Exception e) { fail("open GPX", e.toString()); }
    }

    private static void testRejectMultipleOpen() {
        String json = "{\"routes\":["
                + "{\"name\":\"A\",\"closed\":false,\"points\":[[0,0],[0,0.001]]},"
                + "{\"name\":\"B\",\"closed\":false,\"points\":[[0,0],[0,-0.001]]}]}";
        try {
            RouteModel.parseJson(json, "two-open.json");
            fail("reject multiple open", "accepted multiple open routes");
        } catch (IllegalArgumentException e) {
            check("reject multiple open message", e.getMessage().contains("开放折线") || e.getMessage().contains("单条"));
        }
    }

    private static void testOpenMotionReversal() {
        String json = "{\"name\":\"open\",\"closed\":false,\"points\":[[0,0],[0,0.002],[0.001,0.003]]}";
        RouteModel.RouteSet rs = RouteModel.parseJson(json, "open.json");
        MotionSimulator sim = new MotionSimulator(rs, 390, 470, 2000, 0.3, 5L);
        RouteModel.GeoPoint prev = sim.point();
        double min = Double.MAX_VALUE, max = 0, maxStep = 0;
        int reversals = 0;
        int reversalTicks = 0;
        for (int i = 0; i < 600 && !sim.reachedTarget(); i++) {
            int beforeRev = sim.reversals();
            sim.advance(1.0);
            RouteModel.GeoPoint p = sim.point();
            double d = RouteModel.GeoMath.distance(prev, p);
            boolean turned = sim.reversals() != beforeRev;
            maxStep = Math.max(maxStep, d);
            if (turned) {
                reversalTicks++;
                check("open reversal tick has motion", d > 0.05);
            } else {
                min = Math.min(min, d);
                max = Math.max(max, d);
            }
            reversals = sim.reversals();
            prev = p;
        }
        check("open movement reversed at endpoint", reversals > 0);
        check("open reversal tick observed", reversalTicks > 0);
        check(String.format(Locale.US, "fixed-1s open worst speed %.4f (180-turn)", min), min > 0.05);
        check(String.format(Locale.US, "open 1s max speed %.4f", max), max <= 2.5642);
        check(String.format(Locale.US, "open 1s max step %.3f", maxStep), maxStep < 2.9);
    }

    private static void testOpenScheduledReversal() {
        RouteModel.RouteSet rs = RouteModel.parseJson(
                "{\"name\":\"open\",\"closed\":false,\"points\":[[0,0],[0,0.002],[0.001,0.003]]}",
                "open.json");
        MotionSimulator sim = new MotionSimulator(rs, 390, 470, 2000, 0.3, 5L);
        RouteModel.GeoPoint prev = sim.point();
        double min = Double.MAX_VALUE, max = 0, maxStep = 0;
        double worstDt = 0, worstD = 0, worstTotal = 0;
        int turns = 0;
        int steps = 0;
        int low = 0;
        while (!sim.reachedTarget() && steps++ < 1000) {
            double dt = Math.max(0.001, Math.min(1.0, sim.secondsToNextEndpoint()));
            if (!(dt > 0)) break;
            sim.advance(dt);
            RouteModel.GeoPoint p = sim.point();
            double d = RouteModel.GeoMath.distance(prev, p);
            double speed = d / dt;
            if (speed < min) { min = speed; worstDt = dt; worstD = d; worstTotal = sim.totalDistance(); }
            if (speed < 2.1276) low++;
            max = Math.max(max, speed);
            maxStep = Math.max(maxStep, d);
            turns = sim.reversals();
            prev = p;
        }
        check("scheduled open turns", turns > 0);
        check(String.format(Locale.US,
                "scheduled open worst speed %.4f dt=%.6f d=%.6f total=%.3f low=%d/%d",
                min, worstDt, worstD, worstTotal, low, steps), min > 0.05);
        check(String.format(Locale.US, "scheduled open max speed %.4f", max), max <= 2.5642);
        check(String.format(Locale.US, "scheduled open max step %.3f", maxStep), maxStep < 2.9);
    }

    private static void testOpenReversalSpeedAudit() {
        RouteModel.RouteSet rs = RouteModel.parseJson(
                "{\"name\":\"open\",\"closed\":false,\"points\":[[0,0],[0,0.002],[0.001,0.003]]}",
                "open.json");
        double worst = Double.MAX_VALUE;
        int lowSamples = 0;
        int total = 0;
        int turns = 0;
        for (long seed = 1; seed <= 12; seed++) {
            MotionSimulator sim = new MotionSimulator(rs, 390, 470, 3000, 0.3, seed);
            RouteModel.GeoPoint prev = sim.point();
            for (int i = 0; i < 1200 && !sim.reachedTarget(); i++) {
                int before = sim.reversals();
                sim.advance(1.0);
                RouteModel.GeoPoint p = sim.point();
                double speed = RouteModel.GeoMath.distance(prev, p);
                worst = Math.min(worst, speed);
                total++;
                if (speed < 2.1276) lowSamples++;
                if (sim.reversals() != before) {
                    turns++;
                }
                prev = p;
            }
        }
        check(String.format(Locale.US,
                "fixed-1s open 180-turn audit worstChordSpeed=%.4f lowSamples=%d/%d turns=%d",
                worst, lowSamples, total, turns), turns > 0 && lowSamples > 0);
    }

    private static void testOpenBreakpointSnapshot() {
        String json = "{\"name\":\"open\",\"closed\":false,\"points\":[[0,0],[0,0.002],[0.001,0.003]]}";
        RouteModel.RouteSet rs = RouteModel.parseJson(json, "open.json");
        MotionSimulator sim = new MotionSimulator(rs, 390, 470, 3000, 0.3, 9L);
        while (!sim.reachedTarget() && sim.reversals() == 0) sim.advance(0.5);
        MotionSimulator.Snapshot s = sim.snapshot();
        check("open snapshot marks reversed", s.reversed || sim.reversals() > 0);
        MotionSimulator restored = MotionSimulator.restore(rs, 390, 470, 3000, 0.3, 9L, s);
        check("open snapshot point exact",
                RouteModel.GeoMath.distance(sim.point(), restored.point()) < 1e-9);
        check("open snapshot reversed exact", restored.reversed() == sim.reversed());
        restored.advance(1.0);
        check("open snapshot continues", restored.totalDistance() >= s.total);
    }

    private static void testRejectDisconnected() {
        String json = "{\"routes\":["
                + "{\"name\":\"A\",\"points\":[[0,0],[0,0.001],[0.001,0.001],[0.001,0],[0,0]]},"
                + "{\"name\":\"B\",\"points\":[[0,0.005],[0,0.006],[0.001,0.006],[0.001,0.005],[0,0.005]]}]}";
        try {
            RouteModel.parseJson(json, "disconnected.json");
            fail("reject disconnected", "accepted disconnected loops");
        } catch (IllegalArgumentException e) {
            check("reject disconnected message", e.getMessage().contains("共享路口"));
        }
    }

    private static void testRejectBadCoordinates() {
        String json = "{\"points\":[[91,0],[0,0],[1,1],[0,0]]}";
        try {
            RouteModel.parseJson(json, "bad.json");
            fail("reject bad lat", "accepted lat 91");
        } catch (IllegalArgumentException e) {
            check("reject bad lat message", e.getMessage().contains("坐标无效"));
        }
    }

    private static void testMotionSpeedAndContinuity() {
        RouteModel.RouteSet rs = twoLoops();
        MotionSimulator sim = new MotionSimulator(rs, 390, 470, 5000, 0.5, 42L);
        RouteModel.GeoPoint prev = sim.point();
        double min = Double.MAX_VALUE, max = 0, maxStep = 0, minStep = Double.MAX_VALUE;
        int n = 600;
        double dt = 1.0;
        for (int i = 0; i < n && !sim.reachedTarget(); i++) {
            sim.advance(dt);
            RouteModel.GeoPoint p = sim.point();
            double d = RouteModel.GeoMath.distance(prev, p);
            double speed = d / dt;
            if (speed < min) min = speed;
            max = Math.max(max, speed);
            maxStep = Math.max(maxStep, d);
            minStep = Math.min(minStep, d);
            prev = p;
        }
        check(String.format(Locale.US, "1s output speed min %.4f >= 2.1277 (all points)", min), min >= 2.1276);
        check(String.format(Locale.US, "1s output speed max %.4f <= 2.5642", max), max <= 2.5642);
        check(String.format(Locale.US, "1s max step %.3f m bounded", maxStep), maxStep < 2.9);
        check("1s no zero/scatter step", minStep > 1.5);
        check("1s loop switching occurred", sim.junctionCrossings() > 0);
        check("1s full run did not reach target", !sim.reachedTarget());
    }

    private static void testJitterSpeed() {
        RouteModel.RouteSet rs = twoLoops();
        MotionSimulator sim = new MotionSimulator(rs, 390, 470, 5000, 0.5, 99L);
        double[] pattern = {1.0, 0.9, 1.1, 0.7, 1.3, 0.2, 1.0, 1.4, 0.6, 1.0};
        RouteModel.GeoPoint prev = sim.point();
        double min = Double.MAX_VALUE, max = 0;
        for (int i = 0; i < 900 && !sim.reachedTarget(); i++) {
            double dt = pattern[i % pattern.length];
            sim.advance(dt);
            RouteModel.GeoPoint p = sim.point();
            double speed = RouteModel.GeoMath.distance(prev, p) / dt;
            min = Math.min(min, speed);
            max = Math.max(max, speed);
            prev = p;
        }
        check(String.format(Locale.US, "jitter output speed min %.4f >= 2.1277", min), min >= 2.1276);
        check(String.format(Locale.US, "jitter output speed max %.4f <= 2.5642", max), max <= 2.5642);
    }

    private static void testNarrowPaceUpperBound() {
        String json = "{\"points\":[[0,0],[0,0.01],[0.01,0.01],[0.01,0],[0,0]]}";
        RouteModel.RouteSet rs = RouteModel.parseJson(json, "straight-square");
        for (int pace : new int[]{390, 470}) {
            MotionSimulator sim = new MotionSimulator(rs, pace, pace, 5000, 0, 1);
            RouteModel.GeoPoint before = sim.point();
            sim.advance(1.0);
            double actual = RouteModel.GeoMath.distance(before, sim.point());
            double limit = 1000.0 / pace;
            check(String.format(Locale.US, "%d/%d first-step distance %.9f <= %.9f", pace, pace, actual, limit),
                    actual <= limit + 1e-9);
            check(String.format(Locale.US, "%d/%d first-step distance >= lower", pace, pace),
                    actual >= limit - 1e-9);
        }
    }

    private static void testTargetCrossingJunction() {
        RouteModel.RouteSet rs = twoLoops();
        MotionSimulator probe = new MotionSimulator(rs, 390, 470, 5000, 0.3, 7L);
        double firstLen = probe.routeSet().loops.get(probe.loopIndex()).length;
        MotionSimulator sim = new MotionSimulator(rs, 390, 470, firstLen + 0.5, 0.3, 7L);
        sim.advance(1000.0);
        check("target crossing total exact", Math.abs(sim.totalDistance() - (firstLen + 0.5)) < 1e-6);
        check("target crossing switched", sim.junctionCrossings() >= 1);
        double dj = RouteModel.GeoMath.distance(sim.point(), rs.junction);
        check(String.format(Locale.US, "target crossing point not stuck at junction (%.3f m)", dj), dj > 0.2);
    }

    private static void testTargetFinalCrossingRealistic() {
        RouteModel.RouteSet rs = twoLoops();
        MotionSimulator probe = new MotionSimulator(rs, 390, 470, 5000, 0.3, 7L);
        double firstLen = probe.routeSet().loops.get(probe.loopIndex()).length;
        MotionSimulator sim = new MotionSimulator(rs, 390, 470, firstLen + 0.5, 0.3, 7L);
        while (sim.remainingDistance() > 1.0) sim.advance(1.0);
        RouteModel.GeoPoint prev = sim.point();
        double dt = Math.max(0.01, sim.remainingDistance() / Math.max(0.5, sim.baseSpeed()));
        sim.advance(dt);
        double speed = RouteModel.GeoMath.distance(prev, sim.point()) / dt;
        check("realistic crossing final total", Math.abs(sim.totalDistance() - (firstLen + 0.5)) < 1e-6);
        check("realistic crossing switched", sim.junctionCrossings() >= 1);
        check("realistic crossing point not at junction",
                RouteModel.GeoMath.distance(sim.point(), rs.junction) > 0.2);
        check(String.format(Locale.US, "realistic crossing final speed %.4f", speed),
                speed >= 2.1276 && speed <= 2.5642);
    }

    private static void testFinalTickSpeed() {
        RouteModel.RouteSet rs = twoLoops();
        MotionSimulator sim = new MotionSimulator(rs, 390, 470, 1000, 0.3, 15L);
        RouteModel.GeoPoint prev = sim.point();
        double min = Double.MAX_VALUE, max = 0;
        while (sim.remainingDistance() > 2.0) {
            sim.advance(1.0);
            double d = RouteModel.GeoMath.distance(prev, sim.point());
            min = Math.min(min, d);
            max = Math.max(max, d);
            prev = sim.point();
        }
        double finalSpeed = 0;
        int guard = 0;
        while (!sim.reachedTarget() && guard++ < 20) {
            double finalDt = Math.max(0.005, sim.remainingDistance() / Math.max(0.5, sim.baseSpeed()));
            prev = sim.point();
            sim.advance(finalDt);
            finalSpeed = RouteModel.GeoMath.distance(prev, sim.point()) / finalDt;
        }
        check("final tick reaches target", sim.reachedTarget());
        check(String.format(Locale.US, "final tick speed %.4f >= 2.1277", finalSpeed), finalSpeed >= 2.1276);
        check(String.format(Locale.US, "final tick speed %.4f <= 2.5642", finalSpeed), finalSpeed <= 2.5642);
        check("final tick 1s steps min above range", min >= 2.1276);
        check("final tick 1s steps max below range", max <= 2.5642);
    }

    private static void testJunctionSwitching() {
        RouteModel.RouteSet rs = twoLoops();
        MotionSimulator sim = new MotionSimulator(rs, 390, 470, 900, 0.3, 7L);
        RouteModel.GeoPoint prev = sim.point();
        double maxStep = 0;
        int switches = 0;
        int lastLoop = sim.loopIndex();
        for (int i = 0; i < 4000; i++) {
            sim.advance(0.1);
            RouteModel.GeoPoint p = sim.point();
            maxStep = Math.max(maxStep, RouteModel.GeoMath.distance(prev, p));
            if (sim.loopIndex() != lastLoop) {
                switches++;
                lastLoop = sim.loopIndex();
            }
            prev = p;
        }
        check("multiple junction switches", switches >= 1);
        check("junction no teleport", maxStep < 0.4);
    }

    private static void testCadenceAndPaceRange() {
        RouteModel.RouteSet rs = twoLoops();
        MotionSimulator sim = new MotionSimulator(rs, 390, 470, 10000, 0.3, 1L);
        double min = 999, max = 0, minP = 999, maxP = 0;
        for (int i = 0; i < 2000; i++) {
            sim.advance(0.2);
            double c = sim.cadence(100, 130);
            min = Math.min(min, c); max = Math.max(max, c);
            double p = sim.paceSecPerKm();
            minP = Math.min(minP, p); maxP = Math.max(maxP, p);
        }
        check("pace min in 390..470", minP >= 389.9);
        check("pace max in 390..470", maxP <= 470.1);
        check("cadence lower bound", min >= 99);
        check("cadence upper bound", max <= 131);
        check("cadence actually varies", max - min > 1.0);
    }

    private static void testBreakpointSnapshot() {
        RouteModel.RouteSet rs = twoLoops();
        MotionSimulator a = new MotionSimulator(rs, 390, 470, 5000, 0.3, 33L);
        for (int i = 0; i < 120; i++) a.advance(0.7);
        MotionSimulator.Snapshot s = a.snapshot();
        MotionSimulator b = MotionSimulator.restore(rs, 390, 470, 5000, 0.3, 33L, s);
        check("snapshot total exact", Math.abs(a.totalDistance() - b.totalDistance()) < 1e-9);
        check("snapshot elapsed exact", Math.abs(a.elapsedSeconds() - b.elapsedSeconds()) < 1e-9);
        check("snapshot point exact",
                RouteModel.GeoMath.distance(a.point(), b.point()) < 1e-9);
        check("snapshot crossings exact", a.junctionCrossings() == b.junctionCrossings());
    }

    private static void testBreakpointMidTransition() {
        RouteModel.RouteSet rs = twoLoops();
        MotionSimulator a = new MotionSimulator(rs, 390, 470, 5000, 0.3, 7L);
        MotionSimulator.Snapshot s = null;
        for (int i = 0; i < 2000; i++) {
            a.advance(0.1);
            s = a.snapshot();
            if (s.inTransition) break;
        }
        check("snapshot reached a junction transition", s != null && s.inTransition);
        if (s != null && s.inTransition) {
            MotionSimulator b = MotionSimulator.restore(rs, 390, 470, 5000, 0.3, 7L, s);
            check("mid-transition point exact",
                    RouteModel.GeoMath.distance(a.point(), b.point()) < 1e-9);
            check("mid-transition total exact",
                    Math.abs(a.totalDistance() - b.totalDistance()) < 1e-9);
            b.advance(1.0);
            check("mid-transition can continue", b.totalDistance() >= s.total);
        }
    }

    private static void testCleanupStatus() {
        check("cleanup failure state", "清理失败".equals(Outcome.state(true, "已完成")));
        check("cleanup success keeps terminal", "已完成".equals(Outcome.state(false, "已完成")));
        check("cleanup failure title", "模拟定位清理失败".equals(Outcome.notificationTitle(true, "已完成")));
    }

    private static void testContentDetection() {
        String gpx = "<?xml version='1.0'?><gpx xmlns='http://www.topografix.com/GPX/1/1'>"
                + "<trk><name>content</name><trkseg>"
                + "<trkpt lat='0' lon='0'/><trkpt lat='0' lon='0.001'/>"
                + "<trkpt lat='0.001' lon='0.001'/><trkpt lat='0.001' lon='0'/>"
                + "<trkpt lat='0' lon='0'/></trkseg></trk></gpx>";
        try {
            RouteModel.RouteSet a = RouteModel.parseAuto(gpx, "download-no-extension");
            check("content detection gpx no suffix", a.loops.size() == 1);
        } catch (Exception e) { fail("content detection gpx no suffix", e.toString()); }
        String json = "{\"routes\":["
                + "{\"name\":\"A\",\"points\":[[0,0],[0,0.001],[0.001,0.001],[0.001,0],[0,0]]},"
                + "{\"name\":\"B\",\"points\":[[0,0],[0,-0.001],[-0.001,-0.001],[-0.001,0],[0,0]]}]}";
        try {
            RouteModel.RouteSet b = RouteModel.parseAuto(json, "misnamed-file.gpx");
            check("content detection json over .gpx suffix", b.loops.size() == 2);
        } catch (Exception e) { fail("content detection json over .gpx suffix", e.toString()); }
        try {
            RouteModel.RouteSet c = RouteModel.parseAuto("\uFEFF  " + gpx, "bom.gpx");
            check("content detection BOM", c.loops.size() == 1);
        } catch (Exception e) { fail("content detection BOM", e.toString()); }
        try {
            RouteModel.parseAuto("not a route at all", "unknown.bin");
            fail("content detection reject garbage", "accepted garbage");
        } catch (IllegalArgumentException e) {
            check("content detection reject garbage message", e.getMessage().contains("无法按内容识别"));
        }
    }

    private static void testExampleFiles() {
        try {
            String gpx = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("examples/synthetic-square.gpx")), java.nio.charset.StandardCharsets.UTF_8);
            RouteModel.RouteSet a = RouteModel.parseGpx(gpx, "examples/synthetic-square.gpx");
            check("example gpx parses", a.loops.size() == 1);
            String json = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("examples/synthetic-two-loops.json")), java.nio.charset.StandardCharsets.UTF_8);
            RouteModel.RouteSet b = RouteModel.parseJson(json, "examples/synthetic-two-loops.json");
            check("example json parses", b.loops.size() == 2);
        } catch (Exception e) { fail("example files", e.toString()); }
    }

    private static RouteModel.RouteSet twoLoops() {
        String json = "{\"routes\":["
                + "{\"name\":\"A\",\"points\":[[0,0],[0,0.001],[0.001,0.001],[0.001,0],[0,0]]},"
                + "{\"name\":\"B\",\"points\":[[0,0],[0,-0.001],[-0.001,-0.001],[-0.001,0],[0,0]]}]}";
        return RouteModel.parseJson(json, "two.json");
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; LOG.append("PASS ").append(name).append('\n'); }
        else { failed++; LOG.append("FAIL ").append(name).append('\n'); }
    }

    private static void fail(String name, String why) {
        failed++; LOG.append("FAIL ").append(name).append(" :: ").append(why).append('\n');
    }
}

