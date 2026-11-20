package org.mobileagent.agent;

public final class ModelProfileTest {
    public static void main(String[] args) {
        String base = ModelCatalog.normalizeBase("https://api.example.com");
        check("https://api.example.com/v1".equals(base), "append /v1: " + base);
        base = ModelCatalog.normalizeBase("https://api.example.com/");
        check("https://api.example.com/v1".equals(base), "strip slash: " + base);
        base = ModelCatalog.normalizeBase("https://api.example.com/v1/");
        check("https://api.example.com/v1".equals(base), "keep /v1: " + base);
        base = ModelCatalog.normalizeBase("https://api.example.com/v1/chat/completions");
        check("https://api.example.com/v1".equals(base), "strip completions: " + base);
        base = ModelCatalog.normalizeBase("https://api.example.com/v1/models");
        check("https://api.example.com/v1".equals(base), "strip models: " + base);
        check("https://api.example.com/v1/chat/completions".equals(
                ModelCatalog.chatEndpoint("https://api.example.com")), "chat endpoint");
        boolean rejected = false;
        try {
            ModelCatalog.normalizeBase("http://api.example.com");
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        check(rejected, "http rejected");

        ModelProfile deepseek = ModelProfile.infer("deepseek-v4-pro");
        check(deepseek.contextTokens == 256000, "deepseek context");
        check(deepseek.tools, "deepseek tools");
        check(deepseek.thinking, "deepseek thinking");
        check(!deepseek.vision, "deepseek no vision");

        ModelProfile official = ModelProfile.infer("deepseek-flash");
        check(official.contextTokens == 128000, "official deepseek context");

        ModelService service = ModelService.create("newapi", "https://api.example.com", "sk-x");
        check("api.example.com".equals(service.host()), "service host: " + service.host());
        check("newapi".equals(service.displayName()), "service display");
        check("Key Configured".equals(service.keyState()), "service key state");
        ModelService copy = service.copy();
        check(copy != service && "https://api.example.com".equals(copy.baseUrl),
                "service copy");

        ModelProfile kimi = ModelProfile.infer("kimi-k2.6");
        check(kimi.contextTokens == 256000, "kimi context");
        check(kimi.thinking, "kimi thinking");
        check(!kimi.vision, "kimi no vision");

        ModelProfile gpt = ModelProfile.infer("gpt-6.1");
        check(gpt.contextTokens == 400000, "gpt context");
        check(gpt.vision, "gpt vision");
        check(gpt.thinking, "gpt thinking");

        check(ModelProfile.isKnownBroken("kimi-k2.7-code"), "known broken");
        check(!ModelProfile.isKnownBroken("kimi-k2.6"), "not broken");

        ModelProfile custom = ModelProfile.infer("deepseek-v4-pro");
        custom.contextTokens = 512000;
        custom.maxOutputTokens = 16384;
        custom.tools = false;
        custom.vision = true;
        custom.thinking = false;
        custom.effort = "low";
        ModelProfile restored = ModelProfile.fromJson(custom.toJson(), "fallback");
        check(restored.contextTokens == 512000, "profile context");
        check(restored.maxOutputTokens == 16384, "profile output");
        check(!restored.tools, "profile tools");
        check(restored.vision, "profile vision");
        check(!restored.thinking, "profile thinking");
        check("low".equals(restored.effort), "profile effort");

        System.out.println("MODEL_PROFILE_TEST_OK");
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("FAILED: " + label);
        }
    }
}
