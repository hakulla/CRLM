package org.mobileagent.agent;

import android.content.Context;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

/** Small runtime localization layer for UI strings; code comments are never translated. */
final class LanguageManager {
    static final String DEFAULT = "en";
    private static final String PREF = "ui_language";
    private static final String ASSET_ZH = "lang/zh.json";
    private static final String ASSET_CATALOG = "lang/en-catalog.json";
    private static final Map<String, Map<String, String>> CACHE = new HashMap<>();
    private static final Map<String, String> NAMES = new HashMap<>();

    private LanguageManager() {
    }

    static String current(Context context) {
        return context.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
                .getString(PREF, DEFAULT);
    }

    static void set(Context context, String code) {
        context.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
                .edit().putString(PREF, code == null || code.isEmpty() ? DEFAULT : code).apply();
        CACHE.clear();
    }

    static String t(Context context, String text) {
        return t(context, text, false);
    }

    static String t(Context context, String text, boolean partial) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String code = current(context);
        if (DEFAULT.equals(code)) {
            return text;
        }
        Map<String, String> map = pack(context, code);
        String value = map.get(text);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        if (!partial || text.length() > 160 || text.indexOf('\n') >= 0) {
            return text;
        }
        // Translate stable UI phrases inside formatted labels/status lines.
        String result = text;
        List<Map.Entry<String, String>> entries = new ArrayList<>(map.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
        for (Map.Entry<String, String> entry : entries) {
            String key = entry.getKey();
            if (key.length() >= 4 && result.contains(key)) {
                result = result.replace(key, entry.getValue());
            }
        }
        return result;
    }

    private static final Pattern SENTENCE_BREAK =
            Pattern.compile("(?<=[.!?])(?=[ \\t]*[A-Z])");

    /**
     * Translates UI-owned text, including paragraphs, newline-separated blocks and labels with
     * dynamic values. Model/user content must not be passed here.
     */
    static String tUi(Context context, String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String code = current(context);
        if (DEFAULT.equals(code)) {
            return text;
        }
        return translateUi(pack(context, code), text, 0);
    }

    private static String translateUi(Map<String, String> map, String text, int depth) {
        if (text == null || text.isEmpty() || depth > 8) {
            return text;
        }
        String exact = lookup(map, text);
        if (exact != null) {
            return exact;
        }
        if (text.indexOf('\n') >= 0) {
            String[] lines = text.split("\n", -1);
            StringBuilder out = new StringBuilder();
            boolean changed = false;
            for (int i = 0; i < lines.length; i++) {
                String translated = translateUi(map, lines[i], depth + 1);
                changed |= !translated.equals(lines[i]);
                out.append(translated);
                if (i < lines.length - 1) {
                    out.append('\n');
                }
            }
            if (changed) {
                return out.toString();
            }
        }
        // Longest fixed prefix first: labels such as "Authorized, work area:" + dynamic path.
        String prefix = null;
        for (String key : map.keySet()) {
            if (key.length() < 8 || (key.indexOf(' ') < 0 && !key.endsWith(":"))) {
                continue;
            }
            if (text.startsWith(key) && !text.equals(key)
                    && (prefix == null || key.length() > prefix.length())) {
                prefix = key;
            }
        }
        if (prefix != null) {
            String translatedPrefix = map.get(prefix);
            if (translatedPrefix != null && !translatedPrefix.isEmpty()) {
                String tail = translateUi(map, text.substring(prefix.length()), depth + 1);
                return translatedPrefix + tail;
            }
        }
        String[] sentences = SENTENCE_BREAK.split(text);
        if (sentences.length > 1) {
            StringBuilder out = new StringBuilder();
            boolean changed = false;
            for (String sentence : sentences) {
                String translated = translateUi(map, sentence, depth + 1);
                changed |= !translated.equals(sentence);
                out.append(translated);
            }
            if (changed) {
                return out.toString();
            }
        }
        return text;
    }

    private static String lookup(Map<String, String> map, String text) {
        String value = map.get(text);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        int start = 0;
        int end = text.length();
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        if (start == 0 && end == text.length()) {
            value = map.get(text + "\n");
            return value == null || value.isEmpty() ? null : value.trim();
        }
        String core = text.substring(start, end);
        value = map.get(core);
        if (value == null || value.isEmpty()) {
            value = map.get(core + "\n");
        }
        if (value == null || value.isEmpty()) {
            return null;
        }
        return text.substring(0, start) + value.trim() + text.substring(end);
    }

    static Toast toast(Context context, CharSequence text, int duration) {
        String value = text == null ? "" : text.toString();
        return Toast.makeText(context, tUi(context, value), duration);
    }

    static String label(Context context) {
        String code = current(context);
        if (DEFAULT.equals(code)) {
            return "English";
        }
        Map<String, String> map = pack(context, code);
        String name = NAMES.get(code);
        return name == null || name.isEmpty() ? code : name;
    }

    static List<String> codes(Context context) {
        List<String> out = new ArrayList<>();
        out.add(DEFAULT);
        out.add("zh");
        File dir = new File(new Workspace(context).baseRoot(), "languages");
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (name.endsWith(".json")) {
                    String code = name.substring(0, name.length() - 5);
                    if (!out.contains(code)) {
                        out.add(code);
                    }
                }
            }
        }
        return out;
    }

    static String displayName(Context context, String code) {
        if (DEFAULT.equals(code)) {
            return DEFAULT.equals(current(context)) ? "English" : t(context, "English");
        }
        Map<String, String> map = pack(context, code);
        String name = NAMES.get(code);
        return name == null || name.isEmpty() ? code : name;
    }

    static Map<String, String> pack(Context context, String code) {
        Map<String, String> cached = CACHE.get(code);
        if (cached != null) {
            return cached;
        }
        Map<String, String> map = new HashMap<>();
        try {
            if ("zh".equals(code)) {
                JSONObject object = new JSONObject(read(context.getAssets().open(ASSET_ZH)));
                JSONObject values = object.optJSONObject("map");
                if (values != null) {
                    for (java.util.Iterator<String> it = values.keys(); it.hasNext(); ) {
                        String key = it.next();
                        map.put(key, values.optString(key, key));
                    }
                }
                NAMES.put(code, object.optString("name", "Chinese"));
            } else {
                File file = new File(new File(new Workspace(context).baseRoot(), "languages"),
                        code + ".json");
                if (file.isFile()) {
                    JSONObject object = new JSONObject(read(new FileInputStream(file)));
                    JSONObject values = object.optJSONObject("map");
                    if (values != null) {
                        for (java.util.Iterator<String> it = values.keys(); it.hasNext(); ) {
                            String key = it.next();
                            map.put(key, values.optString(key, key));
                        }
                    }
                    NAMES.put(code, object.optString("name", code));
                }
            }
        } catch (Exception ignored) {
        }
        CACHE.put(code, map);
        return map;
    }

    static List<String> catalog(Context context) {
        List<String> out = new ArrayList<>();
        try {
            org.json.JSONArray array = new org.json.JSONArray(read(
                    context.getAssets().open(ASSET_CATALOG)));
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "");
                if (!value.isEmpty()) {
                    out.add(value);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static void savePack(Context context, String code, String name,
                         Map<String, String> values) throws Exception {
        File dir = new File(new Workspace(context).baseRoot(), "languages");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("Cannot create languages directory");
        }
        JSONObject map = new JSONObject();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            map.put(entry.getKey(), entry.getValue());
        }
        JSONObject root = new JSONObject();
        root.put("name", name);
        root.put("code", code);
        root.put("map", map);
        File target = new File(dir, code + ".json");
        File temp = new File(dir, code + ".json.tmp");
        FileOutputStream out = new FileOutputStream(temp);
        try {
            out.write(root.toString().getBytes("UTF-8"));
        } finally {
            out.close();
        }
        if (!temp.renameTo(target)) {
            throw new IllegalStateException("Cannot save language pack");
        }
    }

    private static final WeakHashMap<View, Boolean> WATCHED = new WeakHashMap<>();

    static void watch(final View root) {
        if (root == null || WATCHED.put(root, Boolean.TRUE) != null) {
            return;
        }
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                apply(root);
            }
        });
    }

    /** Applies exact UI-string translations to a view tree. Dynamic/model content is left alone. */
    static void apply(View root) {
        if (root == null) {
            return;
        }
        Context context = root.getContext();
        if (root instanceof EditText) {
            return;
        }
        if (root instanceof TextView) {
            CharSequence value = ((TextView) root).getText();
            if (value != null) {
                String text = value.toString();
                String translated = t(context, text);
                if (!translated.equals(text)) {
                    ((TextView) root).setText(translated);
                }
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                apply(group.getChildAt(i));
            }
        }
    }

    private static String read(InputStream input) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
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
