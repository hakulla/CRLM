package org.mobileagent.agent;

public final class FailurePolicyTest {
    public static void main(String[] args) {
        FailurePolicy policy = new FailurePolicy();
        check(policy.record("run", "cmake: not found") != null, "capability immediate");
        policy.reset();
        check(policy.record("open_app", "permission denied") != null, "permission immediate");
        policy.reset();
        check(policy.record("inspect", "File does not exist") == null, "input first retry");
        check(policy.record("inspect", "File does not exist") != null, "input second asks");
        policy.reset();
        check(policy.record("web_fetch", "timeout") == null, "transient 1");
        check(policy.record("web_fetch", "timeout") == null, "transient 2");
        check(policy.record("web_fetch", "timeout") != null, "transient 3 asks");
        policy.reset();
        check(policy.record("a", "unknown one") == null, "unknown 1");
        check(policy.record("b", "unknown two") == null, "unknown 2");
        check(policy.record("c", "unknown three") != null, "total 3 asks");
        System.out.println("FAILURE_POLICY_TEST_OK");
    }

    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
