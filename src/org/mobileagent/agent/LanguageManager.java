package org.mobileagent.agent;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String code = current(context);
        if (DEFAULT.equals(code)) {
            return text;
        }
        Map<String, String> map = pack(context, code);
        String value = map.get(text);
        return value == null || value.isEmpty() ? text : value;
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
            return "English";
        }
        if ("zh".equals(code)) {
            return "Chinese";
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
