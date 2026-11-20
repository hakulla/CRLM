package org.mobileagent.agent;

/** Compatibility facade; effects are owned by the registered ToolSpec, never name heuristics. */
public final class ToolPolicy {
    public static final int READ = 1, WRITE = 2, PROCESS = 4, NETWORK = 8, SENSITIVE = 16,
            DEVICE = 32, INSTALL = 64, UNKNOWN = 128, META = 256;
    private static final int RESTRICTED = WRITE | PROCESS | NETWORK | SENSITIVE | DEVICE | INSTALL | UNKNOWN;
    private ToolPolicy() { }
    public static int classify(String name, boolean dryRun) {
        ToolRegistry.ToolSpec spec = ToolRegistry.global().get(name);
        if (spec == null) return UNKNOWN;
        try { return spec.effectsForDryRun(dryRun); }
        catch (RuntimeException e) { return UNKNOWN; }
    }
    public static boolean restricted(int effects) { return (effects & RESTRICTED) != 0; }
    /** Old boolean approvals are not proof of a run-bound grant and cannot bypass ASK. */
    public static String authorize(String mode, String name, boolean dryRun, boolean alwaysAllowed) {
        int effects = classify(name, dryRun);
        if ((effects & UNKNOWN) != 0) return "BLOCKED: Unregistered or unknown side effects";
        return verdict(mode, effects, false);
    }
    public static String authorize(String mode, ToolRegistry.Invocation call, ToolScope scope,
                                   String runId, long policyRevision, String policyFingerprint,
                                   ApprovalGrant grant) {
        if (call == null || scope == null || !scope.allows(call.name)) return "BLOCKED: Tool range not allowed";
        boolean approved = grant != null && grant.matches(runId, scope, policyRevision,
                policyFingerprint, call);
        return verdict(mode, call.effects, approved);
    }
    private static String verdict(String mode, int effects, boolean approved) {
        if (!"auto".equals(mode) && !"ask".equals(mode) && !"readonly".equals(mode))
            return "BLOCKED: Unknown approval mode";
        if ((effects & UNKNOWN) != 0) return "BLOCKED: Unknown side effects";
        if ("readonly".equals(mode) && restricted(effects)) return "BLOCKED: Current Session is Read-only";
        if ("ask".equals(mode) && restricted(effects) && !approved) return "ASK";
        return null;
    }
}
