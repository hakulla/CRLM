package org.mobileagent.agent;

import java.io.File;

/** Compatibility facade. Support is determined by actual GGUF metadata. */
final class LocalTemplates {
    private LocalTemplates() { }
    static boolean supports(String modelPath) {
        try { return modelPath != null && LocalModelAdapter.supports(GgufMetadata.read(new File(modelPath), false)); }
        catch (Exception e) { return false; }
    }
    static String family(String modelPath) {
        return supports(modelPath) ? "gguf-jinja/qwen3-verified" : "unsupported";
    }
}
