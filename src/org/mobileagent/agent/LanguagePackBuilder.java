package org.mobileagent.agent;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds a user-language pack from UI strings with the configured chat model. */
final class LanguagePackBuilder {
    private static final String TAG = "LanguagePack";

    interface Callback {
        void onResult(boolean ok, String code, String message);
    }

    interface Progress {
        void onProgress(String message);
    }

    private LanguagePackBuilder() {
    }

    static void build(final Context context, final String languageName,
                      final Progress progress, final Callback callback) {
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    AppPrefs prefs = new AppPrefs(app);
                    String base = prefs.baseUrl();
                    String model = prefs.model();
                    String key = prefs.apiKey();
                    if (model == null || model.trim().isEmpty()
                            || base == null || base.trim().isEmpty()) {
                        callback.onResult(false, null,
                                "Configure a model and base URL in Settings first.");
                        return;
                    }
                    List<String> source = new ArrayList<>();
                    for (String text : LanguageManager.catalog(app)) {
                        if (isUiString(text)) {
                            source.add(text);
                        }
                    }
                    Map<String, String> translated = new LinkedHashMap<>();
                    int total = source.size();
                    for (int start = 0; start < total; start += 40) {
                        int end = Math.min(total, start + 40);
                        List<String> batch = source.subList(start, end);
                        if (progress != null) {
                            progress.onProgress("Translating " + end + " / " + total);
                        }
                        Map<String, String> values;
                        try {
                            values = translateBatch(base, key, model, languageName, batch);
                        } catch (Throwable e) {
                            Log.w(TAG, "batch " + start + " failed; keeping source text", e);
                            values = new LinkedHashMap<>();
                        }
                        for (String text : batch) {
                            String value = values.get(text);
                            translated.put(text, value == null || value.isEmpty() ? text : value);
                        }
                    }
                    String code = slug(languageName);
                    LanguageManager.savePack(app, code, languageName, translated);
                    LanguageManager.set(app, code);
                    callback.onResult(true, code, translated.size() + " UI strings saved");
                } catch (Throwable e) {
                    callback.onResult(false, null, e.getMessage() == null
                            ? "Language pack failed" : e.getMessage());
                }
            }
        }, "crlm-language-pack").start();
    }

    private static Map<String, String> translateBatch(String base, String key, String model,
                                                      String languageName, List<String> batch)
            throws Exception {
        try {
            JSONArray input = new JSONArray();
            for (String text : batch) {
                input.put(text);
            }
            JSONObject request = new JSONObject();
            request.put("model", model);
            request.put("stream", false);
            request.put("temperature", 0);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content",
                    "You localize user-interface strings. Return only a JSON object "
                            + "with the exact input strings as keys and translations "
                            + "as values. Preserve placeholders such as %s, {name}, "
                            + "URLs, code identifiers, and punctuation semantics. "
                            + "Do not add explanations or markdown."));
            messages.put(new JSONObject().put("role", "user").put("content",
                    "Target language: " + languageName + "\nStrings: " + input));
            request.put("messages", messages);
            String content = request(ModelCatalog.chatEndpoint(base), key, request.toString());
            String json = extractJson(content);
            if (json == null) {
                throw new IllegalStateException("Model did not return a JSON object");
            }
            JSONObject values = new JSONObject(json);
            Map<String, String> out = new LinkedHashMap<>();
            for (String text : batch) {
                String value = values.optString(text, "");
                if (value != null && !value.isEmpty()) {
                    out.put(text, value);
                }
            }
            return out;
        } catch (Exception e) {
            if (batch.size() <= 10) {
                throw e;
            }
            int middle = batch.size() / 2;
            Map<String, String> out = new LinkedHashMap<>();
            out.putAll(translateBatch(base, key, model, languageName,
                    new ArrayList<>(batch.subList(0, middle))));
            out.putAll(translateBatch(base, key, model, languageName,
                    new ArrayList<>(batch.subList(middle, batch.size()))));
            return out;
        }
    }

    private static String request(String endpoint, String key, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(180000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        if (key != null && !key.trim().isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + key.trim());
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        OutputStream output = connection.getOutputStream();
        try {
            output.write(bytes);
        } finally {
            output.close();
        }
        int code = connection.getResponseCode();
        InputStream input = code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String response = read(input);
        connection.disconnect();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + ": "
                    + response.substring(0, Math.min(180, response.length())));
        }
        JSONObject object = new JSONObject(response);
        JSONArray choices = object.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IllegalStateException("Model returned no choices");
        }
        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
        return message == null ? "" : message.optString("content", "");
    }

    private static String extractJson(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : null;
    }

    private static boolean isUiString(String text) {
        if (text == null) {
            return false;
        }
        String value = text.trim();
        if (value.length() < 3 || text.indexOf('|') >= 0
                || text.indexOf("\\p{") >= 0) {
            return false;
        }
        return !value.matches("[A-Za-z0-9_./\\\\-]{1,3}");
    }

    private static String slug(String name) {
        String value = name == null ? "" : name.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (value.isEmpty() || "en".equals(value) || "zh".equals(value)) {
            value = "lang-" + System.currentTimeMillis();
        }
        return value;
    }

    private static String read(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        try {
            while ((n = input.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
        } finally {
            input.close();
        }
        return out.toString("UTF-8");
    }
}
