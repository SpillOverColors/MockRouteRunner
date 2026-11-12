package io.github.dsh.mockroute;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Offline persistence test; uses the File overload, no Android Context needed. */
public final class CheckpointTests {
    private static int passed, failed;
    private static final StringBuilder LOG = new StringBuilder();

    public static void main(String[] args) throws Exception {
        try {
            testRoundTrip();
            testMidTransitionRoundTrip();
            testOpenReversedRoundTrip();
            testEditorReorderPersistence();
            testCorruptFile();
            testBridgeProtocol();
        } catch (Throwable t) {
            failed++;
            LOG.append("FAIL checkpoint harness :: ").append(t).append('\n');
            t.printStackTrace();
        }
        String summary = String.format(java.util.Locale.US,
                "CHECKPOINT TESTS: %d passed, %d failed%n", passed, failed);
        System.out.print(LOG);
        System.out.print(summary);
        if (args.length > 0) {
            Files.write(java.nio.file.Paths.get(args[0]),
                    (LOG.toString() + summary).getBytes(StandardCharsets.UTF_8));
        }
        if (failed > 0) System.exit(1);
    }

    private static void testRoundTrip() throws Exception {
        File dir = Files.createTempDirectory("dsh-checkpoint").toFile();
        File f = new File(dir, "run.properties");
        RouteModel.RouteSet rs = twoLoops();
        MotionSimulator sim = new MotionSimulator(rs, 390, 470, 5000, 0.3, 33L);
        for (int i = 0; i < 120; i++) sim.advance(0.7);
        RunCheckpoint.save(f, "route.json", 390, 470, 5000, 0.3, 100, 130, 33L,
                "com.example.target", sim);
        RunCheckpoint.Saved s = RunCheckpoint.load(f);
        check("checkpoint file written", f.isFile() && f.length() > 0);
        check("checkpoint loaded", s != null);
        if (s != null) {
            check("checkpoint total", Math.abs(s.snapshot.total - sim.totalDistance()) < 1e-9);
            check("checkpoint elapsed", Math.abs(s.snapshot.elapsed - sim.elapsedSeconds()) < 1e-9);
            check("checkpoint package", "com.example.target".equals(s.targetPackage));
            MotionSimulator restored = MotionSimulator.restore(rs, s.fast, s.slow, s.distance,
                    s.lateral, s.seed, s.snapshot);
            check("checkpoint point", RouteModel.GeoMath.distance(sim.point(), restored.point()) < 1e-9);
        }
        boolean replaced = RunCheckpoint.save(f, "route.json", 390, 470, 5000, 0.3, 100, 130, 33L,
                "com.example.target", sim);
        check("checkpoint atomic replace existing", replaced);
        File unwritable = new File(dir, "not-a-directory");
        Files.write(unwritable.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        check("checkpoint save failure reports false",
                !RunCheckpoint.save(new File(unwritable, "run.properties"), "route.json",
                        390, 470, 5000, 0.3, 100, 130, 33L, "", sim));
        f.delete(); unwritable.delete(); dir.delete();
    }

    private static void testMidTransitionRoundTrip() throws Exception {
        File dir = Files.createTempDirectory("dsh-checkpoint").toFile();
        File f = new File(dir, "run.properties");
        RouteModel.RouteSet rs = twoLoops();
        MotionSimulator sim = new MotionSimulator(rs, 390, 470, 5000, 0.3, 7L);
        boolean found = false;
        for (int i = 0; i < 2000; i++) {
            sim.advance(0.1);
            if (sim.snapshot().inTransition) { found = true; break; }
        }
        check("checkpoint reached transition", found);
        if (found) {
            RunCheckpoint.save(f, "route.json", 390, 470, 5000, 0.3, 100, 130, 7L, "", sim);
            RunCheckpoint.Saved s = RunCheckpoint.load(f);
            check("transition checkpoint loaded", s != null && s.snapshot.inTransition);
            if (s != null) {
                MotionSimulator restored = MotionSimulator.restore(rs, 390, 470, 5000, 0.3, 7L, s.snapshot);
                check("transition checkpoint point",
                        RouteModel.GeoMath.distance(sim.point(), restored.point()) < 1e-9);
            }
        }
        f.delete(); dir.delete();
    }

    private static void testOpenReversedRoundTrip() throws Exception {
        File dir = Files.createTempDirectory("dsh-checkpoint").toFile();
        File f = new File(dir, "open.properties");
        RouteModel.RouteSet rs = RouteModel.parseJson(
                "{\"name\":\"open\",\"closed\":false,\"points\":[[0,0],[0,0.002],[0.001,0.003]]}",
                "open.json");
        MotionSimulator sim = new MotionSimulator(rs, 390, 470, 3000, 0.3, 9L);
        while (sim.reversals() == 0) sim.advance(0.5);
        check("checkpoint open reversed", sim.reversed());
        RunCheckpoint.save(f, "open.json", 390, 470, 3000, 0.3, 100, 130, 9L, "", sim);
        RunCheckpoint.Saved s = RunCheckpoint.load(f);
        check("open reversed checkpoint loaded", s != null && s.snapshot.reversed);
        if (s != null) {
            MotionSimulator restored = MotionSimulator.restore(rs, 390, 470, 3000, 0.3, 9L, s.snapshot);
            check("open reversed checkpoint point",
                    RouteModel.GeoMath.distance(sim.point(), restored.point()) < 1e-9);
        }
        f.delete(); dir.delete();
    }

    private static void testEditorReorderPersistence() {
        java.util.List<RouteModel.GeoPoint> pts = new java.util.ArrayList<>();
        pts.add(new RouteModel.GeoPoint(0, 0));
        pts.add(new RouteModel.GeoPoint(0, 0.001));
        pts.add(new RouteModel.GeoPoint(0.001, 0.001));
        pts.add(new RouteModel.GeoPoint(0.001, 0));
        RouteModel.RouteLoop open = new RouteModel.RouteLoop("edited", pts, false);
        RouteModel.RouteSet rs = new RouteModel.RouteSet(pts.get(0),
                java.util.Collections.singletonList(open), "edited-open");
        RouteModel.RouteSet parsed = RouteModel.parseJson(RouteModel.toJson(rs), "edited-open.json");
        check("editor open save/import", parsed.isOpenSingle()
                && parsed.loops.get(0).points.size() == 4);
        java.util.Collections.swap(pts, 1, 2);
        RouteModel.RouteLoop reordered = new RouteModel.RouteLoop("edited", pts, false);
        RouteModel.RouteSet rs2 = new RouteModel.RouteSet(pts.get(0),
                java.util.Collections.singletonList(reordered), "edited-open");
        RouteModel.RouteSet parsed2 = RouteModel.parseJson(RouteModel.toJson(rs2), "edited-open.json");
        check("editor reorder persists", parsed2.loops.get(0).points.get(1).lon > 0.0009
                && parsed2.loops.get(0).points.get(1).lat > 0.0009);
        check("editor open remains open", !parsed2.loops.get(0).closed);
    }

    private static void testCorruptFile() throws Exception {
        File dir = Files.createTempDirectory("dsh-checkpoint").toFile();
        File f = new File(dir, "bad.properties");
        Files.write(f.toPath(), "not-a-checkpoint".getBytes(StandardCharsets.UTF_8));
        check("corrupt checkpoint rejected", RunCheckpoint.load(f) == null);
        f.delete(); dir.delete();
    }

    private static void testBridgeProtocol() {
        check("START ACK exact package",
                BridgeProtocol.startOk("OK START com.x 100 130 1 baseline=5", "com.x"));
        check("START ACK rejects wrong package",
                !BridgeProtocol.startOk("OK START com.y 100 130 1", "com.x"));
        check("RESUME ACK recognized",
                BridgeProtocol.resumeOk("OK RUNNING resumed=1 gen=9"));
        check("RESUME ACK rejects plain OK",
                !BridgeProtocol.resumeOk("OK"));
        check("fresh fallback only for NOT_STARTED",
                BridgeProtocol.resumeNeedsStart("ERR NOT_STARTED"));
        check("auth/target error does not trigger START fallback",
                !BridgeProtocol.resumeNeedsStart("ERR WRONG_TARGET")
                        && !BridgeProtocol.resumeNeedsStart("ERR UNAUTHORIZED"));
        check("null reply does not trigger START fallback",
                !BridgeProtocol.resumeNeedsStart(null));
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
}
