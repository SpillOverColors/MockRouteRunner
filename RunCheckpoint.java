package io.github.dsh.mockroute;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Durable breakpoint for pause/process-death recovery; internal app storage only. */
final class RunCheckpoint {
    private static final String FILE = "run-checkpoint.properties";
    private static final String VERSION = "1";

    private RunCheckpoint() {}

    static final class Saved {
        String routeFile;
        int fast, slow;
        double distance, lateral, cadenceMin, cadenceMax;
        long seed;
        String targetPackage = "";
        MotionSimulator.Snapshot snapshot;
    }

    private static File file(Context c) { return new File(c.getFilesDir(), FILE); }

    static boolean exists(Context c) {
        File f = file(c);
        return f.isFile() && f.length() > 0;
    }

    static void clear(Context c) {
        File f = file(c);
        if (f.exists()) f.delete();
    }

    static boolean save(Context c, String routeFile, int fast, int slow, double distance,
                        double lateral, double cadenceMin, double cadenceMax, long seed,
                        String targetPackage, MotionSimulator sim) {
        return save(file(c), routeFile, fast, slow, distance, lateral, cadenceMin, cadenceMax,
                seed, targetPackage, sim);
    }

    /**
     * Atomic replace: old checkpoint is kept on every failure. Returns false
     * instead of silently claiming success.
     */
    static boolean save(File target, String routeFile, int fast, int slow, double distance,
                        double lateral, double cadenceMin, double cadenceMax, long seed,
                        String targetPackage, MotionSimulator sim) {
        if (sim == null || routeFile == null || target == null) return false;
        File parent = target.getParentFile();
        if (parent == null || !parent.isDirectory()) return false;
        MotionSimulator.Snapshot s = sim.snapshot();
        Properties p = new Properties();
        p.setProperty("version", VERSION);
        p.setProperty("routeFile", routeFile);
        p.setProperty("fast", Integer.toString(fast));
        p.setProperty("slow", Integer.toString(slow));
        p.setProperty("distance", Double.toString(distance));
        p.setProperty("lateral", Double.toString(lateral));
        p.setProperty("cadenceMin", Double.toString(cadenceMin));
        p.setProperty("cadenceMax", Double.toString(cadenceMax));
        p.setProperty("seed", Long.toString(seed));
        p.setProperty("targetPackage", targetPackage == null ? "" : targetPackage);
        p.setProperty("total", Double.toString(s.total));
        p.setProperty("elapsed", Double.toString(s.elapsed));
        p.setProperty("loopIndex", Integer.toString(s.loopIndex));
        p.setProperty("loopOffset", Double.toString(s.loopOffset));
        p.setProperty("crossings", Integer.toString(s.crossings));
        p.setProperty("reversed", Boolean.toString(s.reversed));
        p.setProperty("reversals", Integer.toString(s.reversals));
        p.setProperty("inTransition", Boolean.toString(s.inTransition));
        p.setProperty("transitionOffset", Double.toString(s.transitionOffset));
        p.setProperty("transitionEntry", Double.toString(s.transitionEntry));
        p.setProperty("p0lat", Double.toString(s.p0lat));
        p.setProperty("p0lon", Double.toString(s.p0lon));
        p.setProperty("p1lat", Double.toString(s.p1lat));
        p.setProperty("p1lon", Double.toString(s.p1lon));
        p.setProperty("p2lat", Double.toString(s.p2lat));
        p.setProperty("p2lon", Double.toString(s.p2lon));
        p.setProperty("p3lat", Double.toString(s.p3lat));
        p.setProperty("p3lon", Double.toString(s.p3lon));
        File tmp = new File(parent, target.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            p.store(out, "DSH mock route breakpoint");
        } catch (Exception e) {
            tmp.delete();
            return false;
        }
        try {
            Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (AtomicMoveNotSupportedException e) {
            // Do not fall back to delete+rename: that would lose the old
            // checkpoint on a crash. Keep the old file and report failure.
            tmp.delete();
            return false;
        } catch (Exception e) {
            tmp.delete();
            return false;
        }
    }

    static Saved load(Context c) {
        return load(file(c));
    }

    static Saved load(File f) {
        if (f == null || !f.isFile()) return null;
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(f)) {
            p.load(in);
        } catch (Exception e) {
            return null;
        }
        if (!VERSION.equals(p.getProperty("version"))) return null;
        try {
            Saved s = new Saved();
            s.routeFile = req(p, "routeFile");
            s.fast = Integer.parseInt(req(p, "fast"));
            s.slow = Integer.parseInt(req(p, "slow"));
            s.distance = Double.parseDouble(req(p, "distance"));
            s.lateral = Double.parseDouble(req(p, "lateral"));
            s.cadenceMin = Double.parseDouble(req(p, "cadenceMin"));
            s.cadenceMax = Double.parseDouble(req(p, "cadenceMax"));
            s.seed = Long.parseLong(req(p, "seed"));
            s.targetPackage = p.getProperty("targetPackage", "");
            MotionSimulator.Snapshot sn = new MotionSimulator.Snapshot();
            sn.total = Double.parseDouble(req(p, "total"));
            sn.elapsed = Double.parseDouble(req(p, "elapsed"));
            sn.loopIndex = Integer.parseInt(req(p, "loopIndex"));
            sn.loopOffset = Double.parseDouble(req(p, "loopOffset"));
            sn.crossings = Integer.parseInt(req(p, "crossings"));
            sn.reversed = Boolean.parseBoolean(p.getProperty("reversed", "false"));
            sn.reversals = p.getProperty("reversals") == null ? 0
                    : Integer.parseInt(p.getProperty("reversals"));
            sn.inTransition = Boolean.parseBoolean(req(p, "inTransition"));
            sn.transitionOffset = Double.parseDouble(req(p, "transitionOffset"));
            sn.transitionEntry = Double.parseDouble(req(p, "transitionEntry"));
            sn.p0lat = Double.parseDouble(req(p, "p0lat"));
            sn.p0lon = Double.parseDouble(req(p, "p0lon"));
            sn.p1lat = Double.parseDouble(req(p, "p1lat"));
            sn.p1lon = Double.parseDouble(req(p, "p1lon"));
            sn.p2lat = Double.parseDouble(req(p, "p2lat"));
            sn.p2lon = Double.parseDouble(req(p, "p2lon"));
            sn.p3lat = Double.parseDouble(req(p, "p3lat"));
            sn.p3lon = Double.parseDouble(req(p, "p3lon"));
            s.snapshot = sn;
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    private static String req(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null) throw new IllegalArgumentException("missing " + key);
        return v;
    }
}
