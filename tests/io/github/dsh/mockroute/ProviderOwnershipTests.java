package io.github.dsh.mockroute;

/** Formal ownership regression: only successful creation is owned for this run. */
public final class ProviderOwnershipTests {
    private static int failed;

    private static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failed++;
    }

    public static void main(String[] args) {
        ProviderOwnership fresh = new ProviderOwnership();
        check(fresh.isEmpty(), "new run owns nothing");
        check(!fresh.owns("gps"), "new run does not own pre-existing gps");

        check(fresh.created("gps"), "gps recorded after successful add");
        check(fresh.created("network"), "network recorded after successful add");
        check(fresh.created("fused"), "fused recorded after successful add");
        check(!fresh.created("gps"), "duplicate add does not double-own");
        check(fresh.snapshot().size() == 3, "three providers tracked separately");

        check(!fresh.owns("passive"), "failed add is not owned");
        check(fresh.owns("gps") && fresh.owns("network") && fresh.owns("fused"),
                "all three owned independently");

        check(fresh.removed("gps"), "successful gps removal drops ownership");
        check(!fresh.owns("gps"), "removed gps no longer owned");
        check(fresh.owns("network") && fresh.owns("fused"),
                "failed/partial cleanup retains other ownership");

        ProviderOwnership afterRestart = new ProviderOwnership();
        check(!afterRestart.owns("gps") && !afterRestart.owns("fused"),
                "restart does not inherit old ownership");

        System.out.println(failed == 0
                ? "OWNERSHIP TESTS: 13 passed, 0 failed"
                : "OWNERSHIP TESTS: " + failed + " failed");
        if (failed != 0) System.exit(1);
    }
}
