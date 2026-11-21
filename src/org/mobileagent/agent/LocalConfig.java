package org.mobileagent.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Mirrors every non-secret app setting into a visible JSON file so a
 * same-signature APK reinstall keeps the user's configuration. Secret values
 * (API key, debug bridge token, installer password) never reach shared storage.
 */
final class LocalConfig {
    static final int SCHEMA = 1;
    static final String DIR = "MobileAgent/config";
    static final String NAME = "agent-settings.json";
    private static final long DEBOUNCE_MS = 150L;
    private static final long FLUSH_TIMEOUT_MS = 3000L;

    private static final String SERVICES_PUBLIC = "model_services_public";

    private static final Set<String> EXCLUDED = new HashSet<String>(Arrays.asList(
            "api_key", "model_services", "debug_bridge_token",
            "pending_task_prompt", "pending_screenshots"));

    private static final Set<String> BOOL_KEYS = new HashSet<String>(Arrays.asList(
            "allow_device_control", "allow_web_tools", "allow_hooks",
            "install_password_automation", "auto_resume_interrupted",
            "bridge_auto_reply", "bridge_local_only", "bridge_auto_all",
            "debug_bridge_enabled", "auto_tool_selection", "smart_code_context",
            "lite_harness", "allow_over_limit"));

    private static final Set<String> INT_KEYS = new HashSet<String>(Arrays.asList(
            "max_tokens", "context_chars", "context_tokens", "max_tool_rounds"));

    private static final Set<String> FLOAT_KEYS = new HashSet<String>(Arrays.asList(
            "temperature"));

    private static final Set<String> STRING_KEYS = new HashSet<String>(Arrays.asList(
            "base_url", "model", "agent_profile", "thinking", "local_model_path",
            "active_service", "last_conversation", "model_catalog", "model_profiles",
            "model_protocol_cache"));

    private static final Set<String> STRING_SET_KEYS = new HashSet<String>(Arrays.asList(
            "bridge_auto_apps", "bridge_seen_apps"));

    private static final Set<String> ALL_KEYS = new HashSet<String>();

    static {
        ALL_KEYS.addAll(BOOL_KEYS);
        ALL_KEYS.addAll(INT_KEYS);
        ALL_KEYS.addAll(FLOAT_KEYS);
        ALL_KEYS.addAll(STRING_KEYS);
        ALL_KEYS.addAll(STRING_SET_KEYS);
        ALL_KEYS.add(SERVICES_PUBLIC);
    }

    private static volatile boolean installed;
    private static volatile Context appContext;
    private static volatile SharedPreferences boundPrefs;
    private static volatile ScheduledExecutorService writer;
    private static volatile ScheduledFuture<?> queued;
    private static volatile long lastSavedAt;
    private static volatile int savedKeys;
    private static volatile int lastRestoredCount;
    private static volatile long lastRestoreAt;
    private static volatile String lastError = "";
    private static volatile String lastRestoredKeys = "";
    private static volatile boolean mirrorPaused;

    private LocalConfig() {
    }

    private static final SharedPreferences.OnSharedPreferenceChangeListener LISTENER =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                    if (key != null && key.startsWith("config_")) {
                        return;
                    }
                    scheduleWrite(DEBOUNCE_MS);
                }
            };

    static synchronized void install(Context context, SharedPreferences prefs) {
        if (installed) {
            return;
        }
        appContext = context.getApplicationContext();
        boundPrefs = prefs;
        installed = true;
        readLastSavedAt();
        restoreMissing();
        prefs.registerOnSharedPreferenceChangeListener(LISTENER);
        scheduleWrite(80L);
    }

    static File directory() {
        return new File(Environment.getExternalStorageDirectory(), DIR);
    }

    static File file() {
        return new File(directory(), NAME);
    }

    static String path() {
        return file().getAbsolutePath();
    }

    static boolean exists() {
        File file = file();
        return file.isFile() && file.length() > 0L;
    }

    static String lastError() {
        return lastError;
    }

    /** Fills keys that are absent from preferences. Existing values always win. */
    static synchronized int restoreMissing() {
        SharedPreferences prefs = boundPrefs;
        if (prefs == null) {
            return 0;
        }
        JSONObject root = readFile();
        JSONObject settings = root == null ? null : root.optJSONObject("settings");
        if (settings == null) {
            return 0;
        }
        JSONObject values = new JSONObject();
        int count = 0;
        Iterator<String> keys = settings.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!ALL_KEYS.contains(key) || prefs.contains(realKey(key))) {
                continue;
            }
            try {
                if (applyValue(prefs, key, settings.opt(key))) {
                    values.put(key, settings.opt(key));
                    count++;
                }
            } catch (Throwable ignored) {
            }
        }
        if (count > 0) {
            lastRestoredCount = count;
            lastRestoreAt = System.currentTimeMillis();
            lastRestoredKeys = values.toString();
            scheduleWrite(50L);
        }
        return count;
    }

    static synchronized boolean flushNow() {
        if (mirrorPaused) {
            return false;
        }
        cancelQueued();
        SharedPreferences prefs = boundPrefs;
        if (prefs == null) {
            return false;
        }
        if (writer == null) {
            return writeSnapshot(prefs);
        }
        final SharedPreferences target = prefs;
        java.util.concurrent.Future<?> task = writer.submit(new Runnable() {
            @Override
            public void run() {
                writeSnapshot(target);
            }
        });
        try {
            task.get(FLUSH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return lastError.isEmpty();
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName();
            return false;
        }
    }

    static void flushAsync() {
        if (boundPrefs == null) {
            return;
        }
        scheduleWrite(0L);
    }

    /** Test/debug helper: removes only mirrorable, non-secret keys. */
    static synchronized int forgetKeys(JSONArray keys) {
        SharedPreferences prefs = boundPrefs;
        if (prefs == null || keys == null) {
            return 0;
        }
        SharedPreferences.Editor editor = prefs.edit();
        int removed = 0;
        for (int i = 0; i < keys.length(); i++) {
            String key = keys.optString(i, "").trim();
            String real = realKey(key);
            if (ALL_KEYS.contains(key) && !SERVICES_PUBLIC.equals(key) && prefs.contains(real)) {
                editor.remove(real);
                removed++;
            }
        }
        if (removed > 0) {
            // Paused until the process restarts so the removed keys remain in the
            // file and the next launch can prove startup restore really works.
            mirrorPaused = true;
            cancelQueued();
            editor.apply();
        }
        return removed;
    }

    /** Test/debug helper for scalar settings; rejects unknown and secret keys. */
    static synchronized boolean setValue(String key, Object value) {
        SharedPreferences prefs = boundPrefs;
        if (prefs == null || key == null || !ALL_KEYS.contains(key)) {
            return false;
        }
        SharedPreferences.Editor editor = prefs.edit();
        if (BOOL_KEYS.contains(key) && value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (INT_KEYS.contains(key) && value instanceof Number) {
            editor.putInt(key, ((Number) value).intValue());
        } else if (FLOAT_KEYS.contains(key) && value instanceof Number) {
            editor.putFloat(key, ((Number) value).floatValue());
        } else if (STRING_KEYS.contains(key) && value instanceof String) {
            editor.putString(key, (String) value);
        } else {
            return false;
        }
        editor.apply();
        scheduleWrite(0L);
        return true;
    }

    static String savedLabel(Context context) {
        if (!exists() && lastSavedAt <= 0L) {
            return LanguageManager.t(context, "Waiting for first saving");
        }
        if (!lastError.isEmpty()) {
            return LanguageManager.t(context, "Writing failed");
        }
        return LanguageManager.t(context, "Autosaved · ") + savedKeys
                + LanguageManager.t(context, " Item");
    }

    static String detailText(Context context) {
        StringBuilder out = new StringBuilder();
        out.append(LanguageManager.t(context, "Documents:")).append(path()).append('\n');
        out.append(LanguageManager.t(context, "Saved:")).append(savedKeys)
                .append(LanguageManager.t(context, " items"));
        if (lastSavedAt > 0L) {
            out.append(" · ").append(formatTime(lastSavedAt));
        }
        out.append('\n');
        if (lastRestoreAt > 0L) {
            out.append(LanguageManager.t(context, "Last completion:"))
                    .append(lastRestoredCount).append(LanguageManager.t(context, " items · "))
                    .append(formatTime(lastRestoreAt)).append('\n');
        }
        if (!lastError.isEmpty()) {
            out.append(LanguageManager.t(context, "Error:")).append(lastError).append('\n');
        }
        out.append(LanguageManager.t(context, "Key policy: "))
                .append(LanguageManager.t(context,
                        "API keys, install passwords, and debug tokens are stored only in app-private storage and are not written to this file."));
        return out.toString();
    }

    static JSONObject debugState() {
        JSONObject out = new JSONObject();
        try {
            File file = file();
            out.put("path", path());
            out.put("exists", exists());
            out.put("size", file.isFile() ? file.length() : 0L);
            out.put("savedKeys", savedKeys);
            out.put("lastSavedAt", lastSavedAt);
            out.put("lastRestoredCount", lastRestoredCount);
            out.put("lastRestoreAt", lastRestoreAt);
            out.put("lastRestoredKeys", lastRestoredKeys);
            out.put("lastError", lastError);
            out.put("mirrorPaused", mirrorPaused);
            out.put("secretValuesExcluded", true);
        } catch (Throwable e) {
            lastError = e.getClass().getSimpleName();
        }
        return out;
    }

    static JSONObject effectiveSettings(SharedPreferences prefs) {
        JSONObject out = new JSONObject();
        if (prefs == null) {
            return out;
        }
        try {
            out.put("base_url", prefs.getString("base_url", ""));
            out.put("model", prefs.getString("model", ""));
            out.put("temperature", prefs.getFloat("temperature", 0.2f));
            out.put("max_tokens", prefs.getInt("max_tokens", 4096));
            out.put("thinking", prefs.getString("thinking", ""));
            out.put("context_tokens", prefs.getInt("context_tokens", -1));
            out.put("max_tool_rounds", prefs.getInt("max_tool_rounds", 16));
            out.put("allow_web_tools", prefs.getBoolean("allow_web_tools", false));
            out.put("allow_hooks", prefs.getBoolean("allow_hooks", false));
            out.put("lite_harness", prefs.getBoolean("lite_harness", true));
            out.put("allow_over_limit", prefs.getBoolean("allow_over_limit", false));
            out.put("smart_code_context", prefs.getBoolean("smart_code_context", false));
            out.put("auto_tool_selection", prefs.getBoolean("auto_tool_selection", true));
            out.put("bridge_auto_reply", prefs.getBoolean("bridge_auto_reply", true));
            out.put("debug_bridge_enabled", prefs.getBoolean("debug_bridge_enabled", false));
            out.put("api_key_present", prefs.contains("api_key")
                    && !prefs.getString("api_key", "").trim().isEmpty());
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void scheduleWrite(long delayMs) {
        if (mirrorPaused) {
            return;
        }
        SharedPreferences prefs = boundPrefs;
        if (prefs == null) {
            return;
        }
        ScheduledExecutorService executor = writer;
        if (executor == null) {
            synchronized (LocalConfig.class) {
                if (writer == null) {
                    writer = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable runnable) {
                            Thread thread = new Thread(runnable, "local-config");
                            thread.setDaemon(true);
                            return thread;
                        }
                    });
                }
                executor = writer;
            }
        }
        cancelQueued();
        final SharedPreferences target = prefs;
        try {
            queued = executor.schedule(new Runnable() {
                @Override
                public void run() {
                    writeSnapshot(target);
                }
            }, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            lastError = e.getClass().getSimpleName();
        }
    }

    private static void cancelQueued() {
        ScheduledFuture<?> pending = queued;
        if (pending != null) {
            pending.cancel(false);
            queued = null;
        }
    }

    private static boolean writeSnapshot(SharedPreferences prefs) {
        try {
            JSONObject root = buildSnapshot(prefs);
            if (root == null) {
                return false;
            }
            String text = root.toString(2);
            if (!writeAtomic(file(), text)) {
                if (lastError.isEmpty()) {
                    lastError = "write failed";
                }
                return false;
            }
            lastSavedAt = root.optLong("updatedAt", System.currentTimeMillis());
            savedKeys = root.optJSONObject("settings") == null
                    ? 0 : root.optJSONObject("settings").length();
            lastError = "";
            return true;
        } catch (Throwable e) {
            lastError = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return false;
        }
    }

    private static JSONObject buildSnapshot(SharedPreferences prefs) throws Exception {
        JSONObject root = new JSONObject();
        root.put("schema", SCHEMA);
        root.put("app", appContext == null ? "org.mobileagent.agent" : appContext.getPackageName());
        root.put("versionName", versionName());
        root.put("updatedAt", System.currentTimeMillis());
        root.put("note", "CRLM Auto-maintained settings mirror; overwrite installation retentions.API Key, The key to install the password, debug the token, etc. will not be written in this document.");
        JSONObject settings = new JSONObject();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            if (key == null || EXCLUDED.contains(key) || !ALL_KEYS.contains(key)) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Boolean) {
                settings.put(key, value);
            } else if (value instanceof Integer || value instanceof Long) {
                settings.put(key, value);
            } else if (value instanceof Float) {
                settings.put(key, ((Float) value).doubleValue());
            } else if (value instanceof String) {
                settings.put(key, value);
            } else if (value instanceof Set) {
                if (STRING_SET_KEYS.contains(key)) {
                    settings.put(key, stringArray((Set<?>) value));
                }
            }
        }
        String services = sanitizedServices(prefs.getString("model_services", ""));
        if (!services.isEmpty()) {
            settings.put(SERVICES_PUBLIC, services);
        }
        root.put("settings", settings);
        return root;
    }

    private static boolean applyValue(SharedPreferences prefs, String key, Object value) {
        if (key == null || value == null) {
            return false;
        }
        SharedPreferences.Editor editor = prefs.edit();
        String real = realKey(key);
        if (SERVICES_PUBLIC.equals(key)) {
            if (!(value instanceof String)) {
                return false;
            }
            String services = importableServices((String) value);
            if (services.isEmpty()) {
                return false;
            }
            editor.putString(real, services);
            editor.apply();
            return true;
        }
        if (BOOL_KEYS.contains(key)) {
            if (!(value instanceof Boolean)) {
                return false;
            }
            editor.putBoolean(real, (Boolean) value);
        } else if (INT_KEYS.contains(key)) {
            if (!(value instanceof Number)) {
                return false;
            }
            Number number = (Number) value;
            int intValue = number.intValue();
            if (number.doubleValue() != intValue) {
                return false;
            }
            if ("max_tokens".equals(key)) {
                intValue = Math.max(2048, Math.min(65536, intValue));
            } else if ("max_tool_rounds".equals(key)) {
                intValue = Math.max(8, Math.min(24, intValue));
            } else if ("context_tokens".equals(key)) {
                intValue = intValue == -1 ? -1 : Math.max(8000, Math.min(1000000, intValue));
            } else if ("context_chars".equals(key)) {
                intValue = Math.max(4000, Math.min(2000000, intValue));
            }
            editor.putInt(real, intValue);
        } else if (FLOAT_KEYS.contains(key)) {
            if (!(value instanceof Number)) {
                return false;
            }
            float floatValue = (float) ((Number) value).doubleValue();
            if (Float.isNaN(floatValue)) {
                return false;
            }
            editor.putFloat(real, Math.max(0f, Math.min(2f, floatValue)));
        } else if (STRING_KEYS.contains(key)) {
            if (!(value instanceof String)) {
                return false;
            }
            String text = (String) value;
            if ("base_url".equals(key) || "model".equals(key)) {
                if (text.trim().isEmpty()) {
                    return false;
                }
            }
            if ("thinking".equals(key)
                    && !Arrays.asList("off", "auto", "on").contains(text)) {
                return false;
            }
            if ("last_conversation".equals(key) && !text.matches("[A-Za-z0-9_-]{4,80}")) {
                return false;
            }
            if (("model_catalog".equals(key) || "model_profiles".equals(key)
                    || "model_protocol_cache".equals(key)) && !isJsonObject(text)) {
                return false;
            }
            editor.putString(real, text);
        } else if (STRING_SET_KEYS.contains(key)) {
            if (!(value instanceof JSONArray)) {
                return false;
            }
            JSONArray array = (JSONArray) value;
            Set<String> values = new HashSet<String>();
            for (int i = 0; i < array.length(); i++) {
                String item = array.optString(i, "").trim();
                if (!item.isEmpty()) {
                    values.add(item);
                }
            }
            editor.putStringSet(real, values);
        } else {
            return false;
        }
        editor.apply();
        return true;
    }

    private static String realKey(String key) {
        return SERVICES_PUBLIC.equals(key) ? "model_services" : key;
    }

    private static JSONArray stringArray(Set<?> values) {
        TreeSet<String> sorted = new TreeSet<String>();
        for (Object value : values) {
            if (value instanceof String) {
                sorted.add((String) value);
            }
        }
        JSONArray array = new JSONArray();
        for (String value : sorted) {
            array.put(value);
        }
        return array;
    }

    private static boolean isJsonObject(String text) {
        try {
            new JSONObject(text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String sanitizedServices(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        try {
            JSONArray input = new JSONArray(raw);
            JSONArray output = new JSONArray();
            for (int i = 0; i < input.length(); i++) {
                JSONObject item = input.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String base = item.optString("baseUrl", "").trim();
                if (base.isEmpty()) {
                    continue;
                }
                JSONObject copy = new JSONObject();
                copy.put("id", item.optString("id", "").trim());
                copy.put("name", item.optString("name", "").trim());
                copy.put("baseUrl", base);
                copy.put("apiKey", "");
                copy.put("protocol", ModelProtocols.normalize(item.optString("protocol", "")));
                output.put(copy);
            }
            return output.length() == 0 ? "" : output.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String importableServices(String raw) {
        try {
            JSONArray input = new JSONArray(raw);
            JSONArray output = new JSONArray();
            for (int i = 0; i < input.length(); i++) {
                JSONObject item = input.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String base = item.optString("baseUrl", "").trim();
                if (base.isEmpty()) {
                    continue;
                }
                JSONObject copy = new JSONObject();
                String id = item.optString("id", "").trim();
                copy.put("id", id.isEmpty() ? base : id);
                copy.put("name", item.optString("name", "").trim());
                copy.put("baseUrl", base);
                copy.put("apiKey", "");
                copy.put("protocol", ModelProtocols.normalize(item.optString("protocol", "")));
                output.put(copy);
            }
            return output.length() == 0 ? "" : output.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static JSONObject readFile() {
        File file = file();
        if (!file.isFile()) {
            return null;
        }
        try {
            FileInputStream input = new FileInputStream(file);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try {
                byte[] buffer = new byte[8192];
                int read;
                int total = 0;
                while ((read = input.read(buffer)) != -1 && total < 1024 * 1024) {
                    output.write(buffer, 0, read);
                    total += read;
                }
            } finally {
                input.close();
            }
            String text = output.toString("UTF-8");
            JSONObject root = new JSONObject(text);
            if (root.optInt("schema", 0) > SCHEMA) {
                return null;
            }
            return root;
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName();
            return null;
        }
    }

    private static void readLastSavedAt() {
        JSONObject root = readFile();
        if (root != null) {
            lastSavedAt = root.optLong("updatedAt", 0L);
            JSONObject settings = root.optJSONObject("settings");
            savedKeys = settings == null ? 0 : settings.length();
        }
    }

    private static boolean writeAtomic(File target, String text) {
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            lastError = "mkdirs failed";
            return false;
        }
        File tmp = new File(dir, target.getName() + ".tmp-" + android.os.Process.myPid());
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(tmp, false);
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
            output.close();
            output = null;
            File backup = new File(dir, target.getName() + ".bak");
            if (target.isFile()) {
                backup.delete();
                copyFile(target, backup);
            }
            if (tmp.renameTo(target)) {
                return true;
            }
            if (copyFile(tmp, target)) {
                tmp.delete();
                return true;
            }
            lastError = "replace failed";
            return false;
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return false;
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (Exception ignored) {
                }
            }
            if (tmp.isFile()) {
                tmp.delete();
            }
        }
    }

    private static boolean copyFile(File source, File target) {
        FileInputStream input = null;
        FileOutputStream output = null;
        try {
            input = new FileInputStream(source);
            output = new FileOutputStream(target, false);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
            output.getFD().sync();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (Exception ignored) {
                }
            }
            if (output != null) {
                try {
                    output.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String versionName() {
        Context context = appContext;
        if (context == null) {
            return "";
        }
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static String formatTime(long time) {
        try {
            return new SimpleDateFormat("MM-dd HH:mm").format(new Date(time));
        } catch (Exception e) {
            return "";
        }
    }
}
