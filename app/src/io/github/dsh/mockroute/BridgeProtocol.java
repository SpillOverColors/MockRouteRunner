package io.github.dsh.mockroute;

/** Pure bridge reply contract; no Android imports, unit-testable. */
final class BridgeProtocol {
    private BridgeProtocol() {}

    static boolean startOk(String reply, String pkg) {
        return reply != null && reply.startsWith("OK START " + pkg + " ");
    }

    static boolean resumeOk(String reply) {
        return reply != null && reply.startsWith("OK RUNNING ");
    }

    /** A fresh process has no engine; only this error permits START fallback. */
    static boolean resumeNeedsStart(String reply) {
        return "ERR NOT_STARTED".equals(reply);
    }

    static boolean isError(String reply) {
        return reply == null || reply.startsWith("ERR");
    }
}
