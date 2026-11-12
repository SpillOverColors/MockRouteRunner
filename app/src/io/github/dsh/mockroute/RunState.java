package io.github.dsh.mockroute;

/** Process-local status shared by the foreground service and the activity. */
final class RunState {
    private RunState() {}

    static volatile boolean running;
    static volatile boolean paused;
    static volatile boolean finished;
    static volatile String state = "空闲";
    static volatile String error = "";
    static volatile String loopName = "";
    static volatile String log = "";
    static volatile String pedometerStatus = "未启用";
    static volatile boolean checkpointAvailable;
    static volatile String checkpointMessage = "无";
    static volatile double distance;
    static volatile double target;
    static volatile double paceSecPerKm;
    static volatile double cadence;
    static volatile double lat;
    static volatile double lon;
    static volatile boolean hasPoint;
    static volatile long elapsedMs;
    static volatile int injections;
    static volatile int crossings;

    private static final StringBuilder LOG = new StringBuilder();

    static synchronized void reset(double targetDistance) {
        running = true;
        paused = false;
        finished = false;
        state = "启动中";
        error = "";
        loopName = "";
        log = "";
        LOG.setLength(0);
        pedometerStatus = "待连接";
        checkpointAvailable = false;
        checkpointMessage = "无";
        distance = 0;
        target = targetDistance;
        paceSecPerKm = 0;
        cadence = 0;
        lat = 0;
        lon = 0;
        hasPoint = false;
        elapsedMs = 0;
        injections = 0;
        crossings = 0;
    }

    static synchronized void append(String line) {
        if (LOG.length() > 12000) LOG.delete(0, LOG.length() - 8000);
        LOG.append(line).append('\n');
        log = LOG.toString();
    }

    static synchronized void clearLog() { LOG.setLength(0); log = ""; }
}
