package io.github.dsh.mockroute;

import io.github.dsh.mockroute.RouteModel.GeoPoint;
import io.github.dsh.mockroute.RouteModel.RouteLoop;
import io.github.dsh.mockroute.RouteModel.RouteSet;

import java.util.Locale;
import java.util.Random;

/**
 * Continuous polyline movement. Position = point on the loop + a bounded,
 * smoothly varying lateral offset. No per-tick random scatter and no whole-track shift.
 */
public final class MotionSimulator {
    public static final double MIN_PACE_SEC_PER_KM = 180.0;
    public static final double MAX_PACE_SEC_PER_KM = 600.0;
    public static final double MIN_CADENCE_SPM = 1.0;
    public static final double MAX_CADENCE_SPM = 300.0;

    private final RouteSet routes;
    private final Random random;
    private final double slowSpeed;      // m/s
    private final double fastSpeed;      // m/s
    private final double baseLow, baseHigh;
    private final double target;
    private final double maxLateral;
    private final double lateralScale;
    private final double phase1, phase2, phase3, phase4;

    private RouteLoop loop;
    private int loopIndex;
    private double loopOffset;
    private JunctionPath transition;
    private double transitionOffset;
    private double transitionEntry;
    private double total;
    private double elapsed;
    private int crossings;
    private boolean reversed;
    private int reversals;
    private GeoPoint point;

    public MotionSimulator(RouteSet routes, double fastPaceSecPerKm, double slowPaceSecPerKm,
                           double targetDistanceMeters, double maxLateralMeters, long seed) {
        if (routes == null) throw new IllegalArgumentException("路线为空");
        if (!(fastPaceSecPerKm > 0) || !(slowPaceSecPerKm > 0) || fastPaceSecPerKm > slowPaceSecPerKm) {
            throw new IllegalArgumentException("配速范围无效");
        }
        if (fastPaceSecPerKm < MIN_PACE_SEC_PER_KM - 1e-9
                || slowPaceSecPerKm > MAX_PACE_SEC_PER_KM + 1e-9) {
            throw new IllegalArgumentException("配速须在 180–600 秒/公里（3:00–10:00）");
        }
        double vFast = 1000.0 / fastPaceSecPerKm;
        double vSlow = 1000.0 / slowPaceSecPerKm;
        if (!(targetDistanceMeters > 0) || !Double.isFinite(targetDistanceMeters)) {
            throw new IllegalArgumentException("总距离必须为正数");
        }
        if (!(maxLateralMeters >= 0) || maxLateralMeters > 0.5 || !Double.isFinite(maxLateralMeters)) {
            throw new IllegalArgumentException("位置扰动上限须在 0–0.5 米");
        }
        this.routes = routes;
        this.random = new Random(seed);
        this.slowSpeed = vSlow;
        this.fastSpeed = vFast;
        double margin = 0.10;
        double width = vFast - vSlow;
        if (width > 2 * margin) {
            this.baseLow = vSlow + margin;
            this.baseHigh = vFast - margin;
        } else {
            this.baseLow = this.baseHigh = (vSlow + vFast) / 2.0;
        }
        this.lateralScale = Math.max(0, Math.min(1, (width - 0.02) / 0.40));
        this.target = targetDistanceMeters;
        this.maxLateral = maxLateralMeters;
        this.phase1 = random.nextDouble() * Math.PI * 2;
        this.phase2 = random.nextDouble() * Math.PI * 2;
        this.phase3 = random.nextDouble() * Math.PI * 2;
        this.phase4 = random.nextDouble() * Math.PI * 2;
        this.loop = routes.nextLoop(random, -1);
        this.loopIndex = routes.indexOf(loop);
        this.loopOffset = 0;
        this.reversed = false;
        updatePoint();
    }

    public double targetDistance() { return target; }
    public double totalDistance() { return total; }
    public double remainingDistance() { return Math.max(0, target - total); }
    public double elapsedSeconds() { return elapsed; }
    public double baseSpeed() { return speedAt(elapsed); }
    public double basePaceSecPerKm() { return 1000.0 / speedAt(elapsed); }
    public double paceSecPerKm() { return basePaceSecPerKm(); }
    public int loopIndex() { return loopIndex; }
    public String loopName() { return loop.name; }
    public int junctionCrossings() { return crossings; }
    public boolean isOpenRoute() { return loopsOpen(); }
    private boolean loopsOpen() { return routes.isOpenSingle(); }
    public boolean reversed() { return reversed; }
    public int reversals() { return reversals; }

    /**
     * Time to the next open-route endpoint using the upper speed bound. The
     * service schedules a sample at the endpoint so the final pre-turn step is
     * not forced to straddle the 180-degree reversal.
     */
    public double secondsToNextEndpoint() {
        if (loop == null || loop.closed) return Double.POSITIVE_INFINITY;
        double d = reversed ? loopOffset : Math.max(0, loop.length - loopOffset);
        if (d <= 1e-9) return 1.0;
        return d / fastSpeed;
    }

    public GeoPoint point() { return point; }
    public boolean reachedTarget() { return total >= target - 1e-9; }
    public RouteSet routeSet() { return routes; }

    /** Explicit breakpoint state, including a mid-junction transition. */
    public static final class Snapshot {
        public double total;
        public double elapsed;
        public int loopIndex;
        public double loopOffset;
        public int crossings;
        public boolean reversed;
        public int reversals;
        public boolean inTransition;
        public double transitionOffset;
        public double transitionEntry;
        public double p0lat, p0lon, p1lat, p1lon, p2lat, p2lon, p3lat, p3lon;
    }

    public Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.total = total;
        s.elapsed = elapsed;
        s.loopIndex = loopIndex;
        s.loopOffset = loopOffset;
        s.crossings = crossings;
        s.reversed = reversed;
        s.reversals = reversals;
        if (transition != null) {
            s.inTransition = true;
            s.transitionOffset = transitionOffset;
            s.transitionEntry = transitionEntry;
            s.p0lat = transition.c0.lat; s.p0lon = transition.c0.lon;
            s.p1lat = transition.c1.lat; s.p1lon = transition.c1.lon;
            s.p2lat = transition.c2.lat; s.p2lon = transition.c2.lon;
            s.p3lat = transition.c3.lat; s.p3lon = transition.c3.lon;
        }
        return s;
    }

    /** Restore a previously saved breakpoint without replaying time or distance. */
    public static MotionSimulator restore(RouteSet routes, double fastPaceSecPerKm,
                                          double slowPaceSecPerKm, double targetDistanceMeters,
                                          double maxLateralMeters, long seed, Snapshot s) {
        if (s == null) throw new IllegalArgumentException("断点为空");
        MotionSimulator sim = new MotionSimulator(routes, fastPaceSecPerKm, slowPaceSecPerKm,
                targetDistanceMeters, maxLateralMeters, seed);
        if (!(s.total >= 0) || !Double.isFinite(s.total) || s.total > targetDistanceMeters + 1e-6) {
            throw new IllegalArgumentException("断点距离无效");
        }
        if (s.loopIndex < 0 || s.loopIndex >= routes.loops.size()) {
            throw new IllegalArgumentException("断点路线索引无效");
        }
        sim.loopIndex = s.loopIndex;
        sim.loop = routes.loops.get(s.loopIndex);
        sim.loopOffset = Math.max(0, Math.min(sim.loop.length, s.loopOffset));
        sim.total = s.total;
        sim.elapsed = Math.max(0, s.elapsed);
        sim.crossings = Math.max(0, s.crossings);
        sim.reversed = s.reversed;
        sim.reversals = Math.max(0, s.reversals);
        if (sim.routes.isOpenSingle() && s.loopIndex != 0) {
            throw new IllegalArgumentException("断点开放路线索引无效");
        }
        if (s.inTransition) {
            RouteModel.GeoPoint p0 = new RouteModel.GeoPoint(s.p0lat, s.p0lon);
            RouteModel.GeoPoint p1 = new RouteModel.GeoPoint(s.p1lat, s.p1lon);
            RouteModel.GeoPoint p2 = new RouteModel.GeoPoint(s.p2lat, s.p2lon);
            RouteModel.GeoPoint p3 = new RouteModel.GeoPoint(s.p3lat, s.p3lon);
            sim.transition = new JunctionPath(p0, p1, p2, p3, 10);
            sim.transitionOffset = Math.max(0, Math.min(sim.transition.length, s.transitionOffset));
            sim.transitionEntry = Math.max(0, s.transitionEntry);
        }
        sim.updatePoint();
        return sim;
    }

    /** Advance using actual elapsed monotonic time supplied by the caller, in seconds. */
    public GeoPoint advance(double dtSeconds) {
        if (!(dtSeconds > 0) || !Double.isFinite(dtSeconds)) return point;
        double speed = speedAt(elapsed + dtSeconds / 2.0);
        double desired = Math.min(speed * dtSeconds, Math.max(0, target - total));
        elapsed += dtSeconds;
        GeoPoint before = point;
        advancePath(desired);
        updatePoint();
        if (total < target - 1e-9 && dtSeconds > 0.01) {
            // At a sharp turn the straight-line chord between two output points is
            // shorter than the path. Nudge a little further along the same path so
            // the actual output-point/dt speed does not fall below the user range.
            // Never cross a loop/transition boundary during this correction, so the
            // random transition state is untouched; and restore the last good step
            // rather than ever exceeding the upper bound.
            double minChord = slowSpeed * dtSeconds * 1.002;
            double maxChord = fastSpeed * dtSeconds;
            double targetChord = Math.min(minChord, maxChord);
            double pathRemaining = currentPathRemaining();
            double extraLimit = Math.min(desired * 0.5 + 0.5,
                    Math.max(0, pathRemaining - 0.0005));
            double extra = 0;
            int guard = 0;
            while (RouteModel.GeoMath.distance(before, point) < targetChord
                    && extra < extraLimit && total < target - 1e-9) {
                double step = Math.min(0.02, extraLimit - extra);
                if (step <= 1e-9) break;
                RouteLoop savedLoop = loop;
                int savedIndex = loopIndex;
                double savedOffset = loopOffset;
                JunctionPath savedTransition = transition;
                double savedTransitionOffset = transitionOffset;
                double savedTransitionEntry = transitionEntry;
                double savedTotal = total;
                GeoPoint savedPoint = point;
                advancePath(step);
                extra += step;
                updatePoint();
                if (RouteModel.GeoMath.distance(before, point) > maxChord) {
                    loop = savedLoop;
                    loopIndex = savedIndex;
                    loopOffset = savedOffset;
                    transition = savedTransition;
                    transitionOffset = savedTransitionOffset;
                    transitionEntry = savedTransitionEntry;
                    total = savedTotal;
                    point = savedPoint;
                    break;
                }
                if (++guard > 1000) break;
            }
        }
        if (total < target && target - total < 0.05) {
            advancePath(target - total);
            updatePoint();
        }
        if (total > target) total = target;
        return point;
    }

    /** Move along the current loop/transition path without touching elapsed time. */
    private void advancePath(double distance) {
        if (!loop.closed) {
            advanceOpen(distance);
            return;
        }
        double remaining = Math.max(0, distance);
        int guard = 0;
        while (remaining > 1e-9 && total < target - 1e-9) {
            if (transition != null) {
                double toEnd = transition.length - transitionOffset;
                double step = Math.min(remaining, Math.max(0, target - total));
                if (toEnd > 1e-9) {
                    step = Math.min(step, toEnd);
                    transitionOffset += step;
                    total += step;
                    remaining -= step;
                }
                if (transitionOffset >= transition.length - 1e-9) {
                    transition = null;
                    loopOffset = transitionEntry;
                    if (remaining <= 1e-9 || total >= target - 1e-9) break;
                } else {
                    break;
                }
            } else {
                double toEnd = loop.length - loopOffset;
                if (toEnd < 1e-9) {
                    if (total >= target - 1e-9) break;
                    beginTransition();
                    if (++guard > 100000) throw new IllegalStateException("路线切换次数异常");
                    continue;
                }
                double step = Math.min(remaining, Math.max(0, target - total));
                if (step < toEnd - 1e-9) {
                    loopOffset += step;
                    total += step;
                    remaining -= step;
                    if (total >= target - 1e-9) { total = target; break; }
                    break;
                } else {
                    double used = Math.min(toEnd, Math.max(0, target - total));
                    loopOffset = loop.length;
                    total += used;
                    remaining -= used;
                    if (total >= target - 1e-9) { total = target; break; }
                    if (remaining > 1e-9) beginTransition();
                    else break;
                }
            }
            if (++guard > 100000) throw new IllegalStateException("路线切换次数异常");
        }
        if (total > target) total = target;
    }

    private double currentPathRemaining() {
        if (transition != null) return Math.max(0, transition.length - transitionOffset);
        if (!loop.closed) return reversed ? loopOffset : Math.max(0, loop.length - loopOffset);
        return Math.max(0, loop.length - loopOffset);
    }

    /** Open polyline: walk to an endpoint, stop, then reverse on the next tick. */
    private void advanceOpen(double distance) {
        double remaining = Math.max(0, distance);
        int guard = 0;
        while (remaining > 1e-9 && total < target - 1e-9) {
            double toEnd = reversed ? loopOffset : loop.length - loopOffset;
            if (toEnd < 1e-9) {
                reversed = !reversed;
                reversals++;
                if (++guard > 100000) throw new IllegalStateException("开放路线折返次数异常");
                continue;
            }
            double step = Math.min(remaining, Math.min(toEnd, Math.max(0, target - total)));
            if (step <= 1e-9) break;
            if (reversed) loopOffset -= step;
            else loopOffset += step;
            total += step;
            remaining -= step;
            if (total >= target - 1e-9) { total = target; break; }
            if ((reversed && loopOffset <= 1e-9) || (!reversed && loopOffset >= loop.length - 1e-9)) {
                // Stop exactly at the endpoint. The next scheduled sample flips
                // direction; do not turn inside this output interval.
                if (reversed) loopOffset = 0;
                else loopOffset = loop.length;
                break;
            }
            if (++guard > 100000) throw new IllegalStateException("开放路线折返次数异常");
        }
        if (total > target) total = target;
    }

    private void beginTransition() {
        RouteLoop next = routes.nextLoop(random, loopIndex);
        double entry = Math.min(4.0, Math.max(0.2, next.length * 0.25));
        double[] incoming = loop.directionAt(Math.max(0, loop.length - 0.5));
        double[] outgoing = next.directionAt(entry);
        GeoPoint p0 = routes.junction;
        GeoPoint p3 = next.pointAt(entry);
        GeoPoint p1 = RouteModel.GeoMath.offset(p0, incoming[0] * entry / 3.0, incoming[1] * entry / 3.0);
        GeoPoint p2 = RouteModel.GeoMath.offset(p3, -outgoing[0] * entry / 3.0, -outgoing[1] * entry / 3.0);
        transition = new JunctionPath(p0, p1, p2, p3, 10);
        transitionOffset = 0;
        transitionEntry = entry;
        loopIndex = routes.indexOf(next);
        loop = next;
        loopOffset = loop.length; // not used while transition is active
        crossings++;
    }

    public double cadence(double cadenceMin, double cadenceMax) {
        if (!Double.isFinite(cadenceMin) || !Double.isFinite(cadenceMax)
                || cadenceMin < MIN_CADENCE_SPM || cadenceMax > MAX_CADENCE_SPM
                || cadenceMin > cadenceMax) {
            throw new IllegalArgumentException("步频上下限须在 1–300 spm，且下限 ≤ 上限");
        }
        double mid = (cadenceMin + cadenceMax) / 2.0;
        double amp = (cadenceMax - cadenceMin) / 2.0 * 0.9;
        double w = 0.65 * Math.sin(2 * Math.PI * elapsed / 97.0 + phase3)
                 + 0.35 * Math.sin(2 * Math.PI * elapsed / 41.0 + phase4);
        return mid + amp * w;
    }

    private double speedAt(double t) {
        if (baseHigh <= baseLow) return (baseLow + baseHigh) / 2.0;
        double mid = (baseLow + baseHigh) / 2.0;
        double amp = (baseHigh - baseLow) / 2.0 * 0.9;
        double w = 0.65 * Math.sin(2 * Math.PI * t / 173.0 + phase1)
                 + 0.35 * Math.sin(2 * Math.PI * t / 67.0 + phase2);
        return Math.max(baseLow, Math.min(baseHigh, mid + amp * w));
    }

    private void updatePoint() {
        GeoPoint base = transition != null
                ? transition.pointAt(transitionOffset)
                : loop.pointAt(loopOffset);
        // A bounded, time- and distance-continuous local offset. It is continuous
        // across loop switches because total distance is continuous; no per-tick scatter.
        double envelope = Math.min(1.0, total / 40.0);
        double amp = maxLateral * lateralScale;
        double east = amp * envelope
                * (0.70 * Math.sin(2 * Math.PI * total / 600.0 + phase3)
                 + 0.30 * Math.sin(2 * Math.PI * elapsed / 180.0 + phase4));
        double north = amp * envelope
                * (0.70 * Math.cos(2 * Math.PI * total / 700.0 + phase1)
                 + 0.30 * Math.cos(2 * Math.PI * elapsed / 210.0 + phase2));
        double mag = Math.hypot(east, north);
        if (mag > amp && mag > 1e-12) {
            east *= amp / mag;
            north *= amp / mag;
        }
        point = RouteModel.GeoMath.offset(base, east, north);
    }

    /** Short cubic junction curve: keeps the turn smooth at 1 s sampling. */
    private static final class JunctionPath {
        final GeoPoint c0, c1, c2, c3;
        final GeoPoint[] pts;
        final double[] cum;
        final double length;

        JunctionPath(GeoPoint p0, GeoPoint p1, GeoPoint p2, GeoPoint p3, int segments) {
            c0 = p0; c1 = p1; c2 = p2; c3 = p3;
            pts = new GeoPoint[segments + 1];
            for (int i = 0; i <= segments; i++) {
                pts[i] = bezier(p0, p1, p2, p3, i / (double) segments);
            }
            cum = new double[pts.length + 1];
            double d = 0;
            for (int i = 0; i < pts.length; i++) {
                cum[i] = d;
                if (i + 1 < pts.length) d += RouteModel.GeoMath.distance(pts[i], pts[i + 1]);
            }
            cum[pts.length] = d;
            length = d;
        }

        GeoPoint pointAt(double s) {
            if (length <= 0) return pts[0];
            s = Math.max(0, Math.min(length, s));
            if (s >= length) return pts[pts.length - 1];
            int lo = 0, hi = pts.length;
            while (lo + 1 < hi) {
                int mid = (lo + hi) >>> 1;
                if (cum[mid] <= s) lo = mid; else hi = mid;
            }
            double seg = cum[lo + 1] - cum[lo];
            double f = seg <= 1e-9 ? 0 : (s - cum[lo]) / seg;
            return RouteModel.GeoMath.interpolate(pts[lo], pts[lo + 1], f);
        }

        private static GeoPoint bezier(GeoPoint p0, GeoPoint p1, GeoPoint p2, GeoPoint p3, double t) {
            GeoPoint a = RouteModel.GeoMath.interpolate(p0, p1, t);
            GeoPoint b = RouteModel.GeoMath.interpolate(p1, p2, t);
            GeoPoint c = RouteModel.GeoMath.interpolate(p2, p3, t);
            GeoPoint d = RouteModel.GeoMath.interpolate(a, b, t);
            GeoPoint e = RouteModel.GeoMath.interpolate(b, c, t);
            return RouteModel.GeoMath.interpolate(d, e, t);
        }
    }

    public static String formatPace(double secPerKm) {
        if (!(secPerKm > 0) || !Double.isFinite(secPerKm)) return "—";
        long totalSec = Math.round(secPerKm);
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
    }

    public static String formatElapsed(double seconds) {
        long s = Math.max(0, (long) seconds);
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }
}

