package org.mobileagent.agent;

import java.io.File;
import java.util.List;

final class LocalLlm {
    interface TokenListener { void onToken(String piece); }
    private static final boolean AVAILABLE;
    private volatile long handle;
    private GgufMetadata metadata;
    static {
        boolean loaded = false;
        try { System.loadLibrary("local_llm"); loaded = nativeApiVersion() == 2; }
        catch (Throwable ignored) { }
        AVAILABLE = loaded;
    }
    static boolean available() { return AVAILABLE; }
    synchronized boolean load(String path, int threads, int contextSize) {
        close();
        if (!AVAILABLE || path == null || path.isEmpty()) return false;
        try {
            GgufMetadata m = GgufMetadata.read(new File(path));
            LocalModelAdapter.requireSupported(m);
            handle = nativeLoad(path, threads, contextSize);
            if (handle == 0) return false;
            if (!m.chatTemplate.equals(nativeMetadata(handle, "tokenizer.chat_template"))
                    || !m.architecture.equals(nativeMetadata(handle, "general.architecture"))) {
                close(); throw new IllegalStateException("NATIVE_METADATA_MISMATCH");
            }
            nativeSetIdentity(handle, m.modelSha256, m.tokenizerSha256, m.templateSha256);
            metadata = m;
            return true;
        } catch (java.io.IOException e) { throw new IllegalArgumentException("INVALID_MODEL:" + e.getMessage(), e); }
    }
    synchronized GgufMetadata metadata() { requireLoaded(); return metadata; }
    synchronized String formatChat(List<LocalModelAdapter.Message> messages, boolean addGeneration, boolean thinking) {
        requireLoaded();
        if (messages == null || messages.isEmpty()) throw new IllegalArgumentException("EMPTY_CHAT");
        String[] roles = new String[messages.size()], contents = new String[messages.size()];
        for (int i = 0; i < roles.length; i++) { roles[i] = messages.get(i).role; contents[i] = messages.get(i).content; }
        return nativeFormatChat(handle, roles, contents, addGeneration, thinking);
    }
    synchronized int countTokens(String formatted) { requireLoaded(); return nativeCountTokens(handle, formatted); }
    synchronized int contextSize() { requireLoaded(); return nativeContextSize(handle); }
    synchronized String capability() { requireLoaded(); return nativeCapability(handle); }
    synchronized String generate(String prompt, int maxTokens, float temperature) {
        requireLoaded(); return nativeGenerate(handle, prompt, maxTokens, temperature);
    }
    synchronized boolean generateStream(String prompt, int maxTokens, float temperature, TokenListener listener) {
        requireLoaded(); return nativeGenerateStream(handle, prompt, maxTokens, temperature, listener);
    }
    // A separate lock lets cancel run during synchronized generation, while close waits for JNI completion.
    private final Object lifecycle = new Object();
    void cancel() { synchronized (lifecycle) { if (handle != 0 && AVAILABLE) nativeCancel(handle); } }
    synchronized String info() { return handle == 0 ? "" : nativeInfo(handle); }
    synchronized boolean isLoaded() { return handle != 0; }
    synchronized void close() {
        synchronized (lifecycle) { if (handle != 0) { nativeFree(handle); handle = 0; } }
        metadata = null;
    }
    private void requireLoaded() { if (!AVAILABLE || handle == 0) throw new IllegalStateException("LOCAL_MODEL_NOT_LOADED"); }
    private static native int nativeApiVersion();
    static native void nativeSetIdentity(long handle, String modelSha256, String tokenizerSha256, String templateSha256);
    private static native long nativeLoad(String path, int threads, int contextSize);
    private static native String nativeFormatChat(long handle, String[] roles, String[] contents, boolean addGeneration, boolean thinking);
    private static native int nativeCountTokens(long handle, String formatted);
    private static native int nativeContextSize(long handle);
    private static native String nativeMetadata(long handle, String key);
    private static native String nativeCapability(long handle);
    private static native String nativeGenerate(long handle, String prompt, int maxTokens, float temperature);
    private static native boolean nativeGenerateStream(long handle, String prompt, int maxTokens, float temperature, TokenListener listener);
    private static native void nativeCancel(long handle);
    private static native String nativeInfo(long handle);
    private static native void nativeFree(long handle);
}
