package io.github.dsh.pedometer;

/** Pure control-reply contract; shared by StepControl and offline protocol tests. */
final class ControlProtocol {
    private ControlProtocol() {}

    static String pauseReply(CadenceEngine.PauseResult r, long generation) {
        if (r == CadenceEngine.PauseResult.NOT_STARTED) return "ERR NOT_STARTED";
        return "OK PAUSED already=" + (r == CadenceEngine.PauseResult.ALREADY_PAUSED ? 1 : 0)
                + " gen=" + generation;
    }

    static String resumeReply(CadenceEngine.ResumeResult r, long generation) {
        if (r == CadenceEngine.ResumeResult.NOT_STARTED) return "ERR NOT_STARTED";
        return "OK RUNNING resumed=" + (r == CadenceEngine.ResumeResult.RESUMED ? 1 : 0)
                + " gen=" + generation;
    }

    static boolean isResumeOk(String reply) {
        return reply != null && reply.startsWith("OK RUNNING ");
    }

    /** Only a genuinely unstarted engine permits the bridge's fresh START fallback. */
    static boolean needsFreshStart(String reply) {
        return "ERR NOT_STARTED".equals(reply);
    }
}
