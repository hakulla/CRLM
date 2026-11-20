package org.mobileagent.agent;

/** Ephemeral, exact invocation approval. Never serialize as a permanent tool-name permission. */
public final class ApprovalGrant {
    public final String runId, canonicalRoot, scopeFingerprint, policyFingerprint, schemaHash, argsHash, resourcesHash;
    public final long policyRevision;
    private final ToolRegistry.Invocation call;
    private ApprovalGrant(String runId, ToolScope scope, long revision, String fingerprint, ToolRegistry.Invocation call) {
        this.runId = runId;
        this.canonicalRoot = call.canonicalRoot;
        this.scopeFingerprint = scope.fingerprint();
        this.policyRevision = revision;
        this.policyFingerprint = fingerprint;
        this.schemaHash = call.schemaHash;
        this.argsHash = call.argsHash;
        this.resourcesHash = call.resourcesHash;
        this.call = call;
    }
    public static ApprovalGrant create(String runId, ToolScope scope, long policyRevision,
                                       String policyFingerprint, ToolRegistry.Invocation call) {
        if (runId == null || runId.isEmpty() || scope == null || scope.isInherit() || call == null
                || !scope.allows(call.name) || policyRevision < 0 || policyFingerprint == null || policyFingerprint.isEmpty())
            throw new IllegalArgumentException("Incomplete/denied approval snapshot");
        return new ApprovalGrant(runId, scope, policyRevision, policyFingerprint, call);
    }
    public boolean matches(String runId, ToolScope scope, long policyRevision,
                           String policyFingerprint, ToolRegistry.Invocation current) {
        return this.runId.equals(runId) && scope != null && !scope.isInherit()
                && scope.allows(call.name) && scopeFingerprint.equals(scope.fingerprint())
                && this.policyRevision == policyRevision && this.policyFingerprint.equals(policyFingerprint)
                && call.sameAs(current);
    }
}
