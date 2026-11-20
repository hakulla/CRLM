package org.mobileagent.agent;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.graphics.Typeface;

import java.util.Locale;

/** Small text formatting helpers shared by chat and settings pages. */
final class UiText {
    private UiText() {
    }

    static CharSequence markdown(String text) {
        if (text.length() > 20000) {
            return text;
        }
        SpannableStringBuilder builder = new SpannableStringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("**", i)) {
                int end = text.indexOf("**", i + 2);
                if (end > i + 2) {
                    int start = builder.length();
                    builder.append(text, i + 2, end);
                    builder.setSpan(new StyleSpan(Typeface.BOLD), start, builder.length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            if (text.charAt(i) == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > i + 1) {
                    int start = builder.length();
                    builder.append(text, i + 1, end);
                    builder.setSpan(new TypefaceSpan("monospace"), start, builder.length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    builder.setSpan(new BackgroundColorSpan(Color.rgb(38, 38, 38)),
                            start, builder.length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            builder.append(text.charAt(i));
            i++;
        }
        return builder;
    }

    static String oneLine(String text, int limit) {
        String value = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').trim();
        while (value.contains("  ")) {
            value = value.replace("  ", " ");
        }
        return value.length() <= limit ? value : value.substring(0, limit) + "...";
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024f);
        }
        return String.format(Locale.US, "%.2f MB", bytes / (1024f * 1024f));
    }
}
