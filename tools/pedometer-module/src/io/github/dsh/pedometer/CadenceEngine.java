package io.github.dsh.pedometer;

/**
 * Pure-JVM cadence state and generation clock. No Android imports, so the
 * pause/stop cancellation and actual-active-time rules can be tested offline.
 */
final class CadenceEngine {
    enum ResumeResult { NOT_STARTED, RESUMED, ALREADY_RUNNING }
    enum PauseResult { NOT_STARTED, PAUSED, ALREADY_PAUSED }

    static final class Tick {
        final long generation;
        final long timestampNanos;
        final int counterValue;
        Tick(long generation, long timestampNanos, int counterValue) {
            this.generation = generation;
            this.timestampNanos = timestampNanos;
            this.counterValue = counterValue;
        }
    }

    private boolean running;
    private boolean paused;
    private long generation;
    private long lastEventNanos;
    private long steps;
    private long baseline;
    private long counterFloor;
    private long lastRealCounter;
    private double min = 100, max = 130;
    private double phase;
    private double motionSeconds;

    synchronized void start(double lo, double hi, long seed, long nowNanos, long realCounter) {
        min = lo;
        max = hi;
        java.util.Random random = new java.util.Random(seed);
        phase = random.nextDouble() * Math.PI * 2;
        motionSeconds = 0;
        baseline = Math.max(realCounter, counterFloor);
        steps = baseline;
        lastEventNanos = nowNanos;
        running = true;
        paused = false;
        generation++;
    }

    synchronized PauseResult pause() {
        if (!running) return PauseResult.NOT_STARTED;
        if (paused) return PauseResult.ALREADY_PAUSED;
        paused = true;
        generation++;
        return PauseResult.PAUSED;
    }

    synchronized ResumeResult resume(long nowNanos) {
        if (!running) return ResumeResult.NOT_STARTED;
        if (!paused) return ResumeResult.ALREADY_RUNNING;
        paused = false;
        lastEventNanos = nowNanos;
        generation++;
        return ResumeResult.RESUMED;
    }

    synchronized void stop() {
        if (!running) return;
        running = false;
        paused = false;
        generation++;
    }

    synchronized boolean isRunning() { return running; }
    synchronized boolean isPaused() { return paused; }
    synchronized long generation() { return generation; }
    synchronized long steps() { return steps; }
    synchronized long baseline() { return baseline; }
    synchronized long counterFloor() { return counterFloor; }
    synchronized long lastRealCounter() { return lastRealCounter; }
    synchronized double motionSeconds() { return motionSeconds; }

    synchronized void noteReal(long value) {
        if (value > lastRealCounter) lastRealCounter = value;
    }

    synchronized boolean deliveryActive(long gen) {
        return running && !paused && generation == gen;
    }

    synchronized double cadence() {
        double mid = (min + max) / 2.0;
        double amp = (max - min) / 2.0 * 0.9;
        return mid + amp * Math.sin(2 * Math.PI * motionSeconds / 97.0 + phase);
    }

    /** One synthetic step. motionSeconds advances by actual active elapsed time. */
    synchronized Tick tick(long nowNanos) {
        if (!running || paused) return null;
        long dtNanos = Math.max(0, nowNanos - lastEventNanos);
        lastEventNanos = nowNanos;
        motionSeconds += dtNanos / 1_000_000_000.0;
        steps++;
        if (steps > counterFloor) counterFloor = steps;
        int value = steps > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) steps;
        return new Tick(generation, nowNanos, value);
    }

    synchronized long nextIntervalMs() {
        double intervalMs = 60_000.0 / Math.max(1.0, cadence());
        return Math.max(1L, Math.round(intervalMs));
    }
}
