package org.mobileagent.agent;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.util.Log;

import java.util.LinkedHashSet;
import java.util.Locale;

/** Read a page in the user's real browser, where login cookies and JS already work. */
final class BrowserReader {
    private static final int MAX_TEXT = 40_000;
    private static final int MAX_SCROLLS = 8;

    static final class Result {
        final String data;
        final String error;

        private Result(String data, String error) {
            this.data = data;
            this.error = error;
        }

        static Result ok(String data) { return new Result(data, null); }
        static Result fail(String error) { return new Result(null, error); }
    }

    private BrowserReader() { }

    static Result read(Context context, String url, int scrolls) {
        if (context == null || url == null || url.trim().isEmpty()) {
            return Result.fail("Missing address");
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return Result.fail("Can not wait for browser reading in main thread");
        }
        AgentAccessibilityService service = AgentAccessibilityService.getInstance();
        if (service == null) {
            return Result.fail("Accessibility services are not enabled, and browser pages cannot be read");
        }
        String target = url.trim();
        Log.i("AgentBrowser", "open " + target);
        if (!openBrowser(context, target)) {
            return Result.fail("Could not open mobile browser");
        }

        String dump = "";
        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            sleep(500);
            try {
                String pkg = service.currentPackage();
                if (pkg.isEmpty() || pkg.equals(context.getPackageName())
                        || "android".equals(pkg)) {
                    continue;
                }
                dump = service.dumpAllText();
                if (dump.length() > 80) {
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
        if (dump.length() < 40) {
            bringAppFront(context);
            return Result.fail("Browser does not load readable pages; may need to login or pages are still loaded");
        }
        Log.i("AgentBrowser", "first dump chars=" + dump.length());
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        collectLines(dump, lines);
        int noNew = 0;
        int limit = Math.max(1, Math.min(scrolls <= 0 ? 6 : scrolls, MAX_SCROLLS));
        for (int i = 0; i < limit; i++) {
            int before = lines.size();
            try {
                service.swipe("up");
            } catch (Throwable error) {
                break;
            }
            sleep(700);
            try {
                collectLines(service.dumpAllText(), lines);
            } catch (Throwable ignored) {
            }
            if (lines.size() == before) {
                noNew++;
                if (noNew >= 2) break;
            } else {
                noNew = 0;
            }
        }
        bringAppFront(context);
        Log.i("AgentBrowser", "collected lines=" + lines.size());
        if (lines.isEmpty()) {
            return Result.fail("Browser page without readable text");
        }
        StringBuilder out = new StringBuilder();
        out.append("Source:").append(target).append('\n')
                .append("Readable: Accessible mobile browser (using log-in browser environments)\n\n");
        int used = 0;
        for (String line : lines) {
            if (used + line.length() + 1 > MAX_TEXT) {
                out.append("\n...[Text has been cut.]");
                break;
            }
            out.append(line).append('\n');
            used += line.length() + 1;
        }
        return Result.ok(out.toString().trim());
    }

    private static boolean openBrowser(Context context, String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.getPackageManager().getPackageInfo("com.android.chrome", 0);
            intent.setPackage("com.android.chrome");
        } catch (Exception ignored) {
        }
        try {
            context.startActivity(intent);
            return true;
        } catch (Throwable first) {
            try {
                Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallback);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    private static void bringAppFront(Context context) {
        try {
            Intent intent = new Intent(context, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            context.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    private static void collectLines(String dump, LinkedHashSet<String> lines) {
        if (dump == null || dump.isEmpty()) {
            return;
        }
        String[] parts = dump.split("\n");
        for (String raw : parts) {
            String line = raw == null ? "" : raw.trim();
            if (line.length() < 2 || isBrowserChrome(line)) {
                continue;
            }
            lines.add(line);
            if (lines.size() > 1200) {
                return;
            }
        }
    }

    private static boolean isBrowserChrome(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.contains("share.my.cn/claw") || lower.contains("file_preview")) {
            return true;
        }
        String[] noise = {"Share", "More", "Refresh", "Home Page", "Tab", "Close", "Back", "Forward!",
                "Download", "Find in Page", "Translation", "Page Information", "Add to Main Screen", "Settings",
                "Bookmarks", "History", "New Tab", "Toggle Tab", "Menu", "Open",
                "Copy", "Select All", "Paste", "Cancel", "Completed", "Edit", "Search",
                "chrome", "google", "Account", "Login", "Address Bar"};
        for (String value : noise) {
            if (line.equals(value) || lower.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
