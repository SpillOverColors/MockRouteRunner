package io.github.dsh.mockroute;

/** Pure status decision so the cleanup-failure rule is testable without Android. */
public final class Outcome {
    private Outcome() {}

    public static String state(boolean cleanupFailed, String terminal) {
        return cleanupFailed ? "清理失败" : terminal;
    }

    public static String notificationTitle(boolean cleanupFailed, String terminal) {
        return cleanupFailed ? "模拟定位清理失败" : "模拟定位" + terminal;
    }
}
