package org.mobileagent.agent;

import java.util.ArrayList;
import java.util.List;

/** Profiles identify real template bytes and tokenizer metadata, never a filename. */
public final class LocalModelAdapter {
    public static final String VERIFIED_QWEN3_TEMPLATE = "5da44855ab7e0641774d5ed0ff0b4f89671d01df7d316e54d06557a962211cb4";
    public static final String VERIFIED_QWEN3_TOKENIZER = "479c4d851e505fddbe27dfac4766f6b2eca2a97af5e22782464c9d90b059c284";
    public static final class Message {
        public final String role, content;
        public Message(String role, String content) {
            if (!"system".equals(role) && !"user".equals(role) && !"assistant".equals(role) && !"tool".equals(role))
                throw new IllegalArgumentException("UNSUPPORTED_ROLE:" + role);
            if (content == null || content.indexOf('\0') >= 0) throw new IllegalArgumentException("INVALID_CONTENT");
            this.role = role; this.content = content;
        }
    }
    public static boolean supports(GgufMetadata m) {
        return m != null && "qwen3".equals(m.architecture)
                && VERIFIED_QWEN3_TEMPLATE.equals(m.templateSha256)
                && VERIFIED_QWEN3_TOKENIZER.equals(m.tokenizerSha256)
                && "gpt2".equals(m.value("tokenizer.ggml.model"))
                && "151645".equals(m.value("tokenizer.ggml.eos_token_id"))
                && !"true".equals(m.value("tokenizer.ggml.add_bos_token"))
                && !"true".equals(m.value("tokenizer.ggml.add_eos_token"))
                && (m.value("qwen3.pooling_type").isEmpty() || "0".equals(m.value("qwen3.pooling_type")));
    }
    public static void requireSupported(GgufMetadata m) {
        if (!supports(m)) throw new IllegalArgumentException("UNSUPPORTED_CHAT_TEMPLATE:" + (m == null ? "missing" : m.templateSha256));
    }
    /** Transitional raw ChatML input is decoded into roles then re-rendered by llama Jinja.
     * Non-ChatML strings are user messages; ambiguous/malformed framed input is rejected. */
    static List<Message> legacyMessages(String raw) {
        if (raw == null || raw.isEmpty()) throw new IllegalArgumentException("EMPTY_PROMPT");
        List<Message> result = new ArrayList<>();
        if (!raw.startsWith("<|im_start|>")) { result.add(new Message("user", raw)); return result; }
        int pos = 0;
        while (pos < raw.length()) {
            if (!raw.startsWith("<|im_start|>", pos)) throw new IllegalArgumentException("INVALID_LEGACY_CHAT");
            pos += 12;
            int nl = raw.indexOf('\n', pos);
            if (nl < 0) throw new IllegalArgumentException("INVALID_LEGACY_CHAT");
            String role = raw.substring(pos, nl); pos = nl + 1;
            if ("assistant".equals(role) && (pos == raw.length() || raw.substring(pos).equals("<think>\n\n</think>\n\n"))) break;
            int end = raw.indexOf("<|im_end|>", pos);
            if (end < 0 || raw.substring(pos, end).contains("<|im_start|>")) throw new IllegalArgumentException("INVALID_LEGACY_CHAT");
            result.add(new Message(role, raw.substring(pos, end))); pos = end + 10;
            if (pos < raw.length() && raw.charAt(pos) == '\n') pos++;
        }
        if (result.isEmpty()) throw new IllegalArgumentException("EMPTY_CHAT");
        return result;
    }
    private LocalModelAdapter() { }
}
