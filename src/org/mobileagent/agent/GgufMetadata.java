package org.mobileagent.agent;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded GGUF v2/v3 reader; arrays are scanned, never materialized. No network. */
public final class GgufMetadata {
    public final String architecture, chatTemplate, templateSha256, tokenizerSha256, modelSha256;
    public final Map<String, String> values;
    public final long bytes;

    private GgufMetadata(Map<String, String> data, String tokenizer, String model, long size) {
        values = Collections.unmodifiableMap(data);
        architecture = value("general.architecture");
        chatTemplate = value("tokenizer.chat_template");
        templateSha256 = sha256(chatTemplate.getBytes(StandardCharsets.UTF_8));
        tokenizerSha256 = tokenizer;
        modelSha256 = model;
        bytes = size;
    }

    public String value(String key) { String v = values.get(key); return v == null ? "" : v; }

    public static GgufMetadata read(File file) throws IOException { return read(file, true); }

    public static GgufMetadata read(File file, boolean hashModel) throws IOException {
        Map<String, String> data = new LinkedHashMap<>();
        MessageDigest tokenizer = digest();
        long size = file.length();
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (u32(in) != 0x46554747L) throw new IOException("INVALID_GGUF_MAGIC");
            long version = u32(in);
            if (version != 2 && version != 3) throw new IOException("UNSUPPORTED_GGUF_VERSION");
            long tensors = u64(in), count = u64(in);
            if (tensors < 1 || tensors > 1000000 || count < 1 || count > 1000000)
                throw new IOException("INVALID_GGUF_COUNTS");
            for (long i = 0; i < count; i++) {
                long start = in.getFilePointer();
                String key = string(in);
                int type = (int) u32(in);
                String value = scalar(in, type, 0);
                if (data.containsKey(key)) throw new IOException("DUPLICATE_GGUF_KEY");
                data.put(key, value);
                long end = in.getFilePointer();
                if (key.startsWith("tokenizer.") && !key.startsWith("tokenizer.chat_template")) {
                    in.seek(start);
                    byte[] b = new byte[65536];
                    for (long left = end - start; left > 0;) {
                        int n = (int) Math.min(left, b.length);
                        in.readFully(b, 0, n); tokenizer.update(b, 0, n); left -= n;
                    }
                    in.seek(end);
                }
            }
            if (in.getFilePointer() >= size) throw new IOException("TRUNCATED_GGUF");
        }
        if (data.get("general.architecture") == null || data.get("tokenizer.ggml.model") == null)
            throw new IOException("MISSING_GGUF_MODEL_OR_TOKENIZER");
        String model = hashModel ? hashFile(file) : "";
        if (size != file.length()) throw new IOException("MODEL_CHANGED_DURING_READ");
        return new GgufMetadata(data, hex(tokenizer.digest()), model, size);
    }

    private static String scalar(RandomAccessFile in, int type, int depth) throws IOException {
        switch (type) {
            case 0: return Integer.toString(in.readUnsignedByte());
            case 1: return Byte.toString(in.readByte());
            case 2: return Integer.toString(Short.reverseBytes(in.readShort()) & 0xffff);
            case 3: return Short.toString(Short.reverseBytes(in.readShort()));
            case 4: return Long.toString(u32(in));
            case 5: return Integer.toString((int) u32(in));
            case 6: return Float.toString(Float.intBitsToFloat((int) u32(in)));
            case 7: int b = in.readUnsignedByte(); if (b > 1) throw new IOException("INVALID_GGUF_BOOL"); return Boolean.toString(b == 1);
            case 8: return string(in);
            case 9:
                if (depth != 0) throw new IOException("NESTED_GGUF_ARRAY");
                int element = (int) u32(in); long count = u64(in);
                if (element == 9 || count > 10000000) throw new IOException("INVALID_GGUF_ARRAY");
                for (long i = 0; i < count; i++) scalar(in, element, depth + 1);
                return "array[" + count + "]";
            case 10:
                long unsigned = Long.reverseBytes(in.readLong());
                return unsigned >= 0 ? Long.toString(unsigned)
                        : java.math.BigInteger.valueOf(unsigned & Long.MAX_VALUE).setBit(63).toString();
            case 11: return Long.toString(Long.reverseBytes(in.readLong()));
            case 12: return Double.toString(Double.longBitsToDouble(Long.reverseBytes(in.readLong())));
            default: throw new IOException("INVALID_GGUF_TYPE:" + type);
        }
    }

    private static long u32(RandomAccessFile in) throws IOException { return Integer.reverseBytes(in.readInt()) & 0xffffffffL; }
    private static long u64(RandomAccessFile in) throws IOException {
        long v = Long.reverseBytes(in.readLong());
        if (v < 0) throw new IOException("GGUF_LENGTH_OVERFLOW");
        return v;
    }
    private static String string(RandomAccessFile in) throws IOException {
        long n = u64(in);
        if (n > 16 * 1024 * 1024 || n > in.length() - in.getFilePointer()) throw new IOException("INVALID_GGUF_STRING_LENGTH");
        byte[] b = new byte[(int) n]; in.readFully(b);
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString(); }
        catch (CharacterCodingException e) { throw new IOException("INVALID_GGUF_UTF8", e); }
    }
    public static String hashFile(File file) throws IOException {
        MessageDigest d = digest();
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] b = new byte[1024 * 1024]; int n;
            while ((n = in.read(b)) != -1) d.update(b, 0, n);
        }
        return hex(d.digest());
    }
    public static String sha256(byte[] bytes) { return hex(digest().digest(bytes)); }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) { s.append(Character.forDigit((b >>> 4) & 15, 16)); s.append(Character.forDigit(b & 15, 16)); }
        return s.toString();
    }
}
