package org.mobileagent.agent;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Light Markdown Block Renderer: Output of the model Markdown ♪ To the original View Blocks,
 * Supports header levels, tables, tables, indents, references, dividing lines and paragraphs.
 */
final class UiMarkdown {

    private UiMarkdown() {
    }

    static void render(Context ctx, LinearLayout host, String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                i++;
                continue;
            }
            // Table: Current Rows | And the next line is split lines
            if (trimmed.indexOf('|') >= 0 && i + 1 < lines.length && isTableDivider(lines[i + 1])) {
                List<String> block = new ArrayList<>();
                block.add(line);
                int j = i + 1;
                while (j < lines.length && lines[j].trim().indexOf('|') >= 0) {
                    block.add(lines[j]);
                    j++;
                }
                host.addView(table(ctx, block));
                i = j;
                continue;
            }
            if (isDivider(trimmed)) {
                host.addView(divider(ctx));
                i++;
                continue;
            }
            if (trimmed.startsWith(">")) {
                List<String> quote = new ArrayList<>();
                int j = i;
                while (j < lines.length && lines[j].trim().startsWith(">")) {
                    quote.add(lines[j].trim().substring(1).trim());
                    j++;
                }
                host.addView(quote(ctx, join(quote)));
                i = j;
                continue;
            }
            int level = headingLevel(trimmed);
            if (level > 0) {
                host.addView(heading(ctx, trimmed.substring(level).trim(), level));
                i++;
                continue;
            }
            if (isListLine(trimmed)) {
                List<String> items = new ArrayList<>();
                int j = i;
                while (j < lines.length) {
                    String t = lines[j].trim();
                    if (t.isEmpty()) {
                        break;
                    }
                    if (isListLine(t)) {
                        items.add(t);
                    } else if (!items.isEmpty()) {
                        // Resumed in previous item
                        items.set(items.size() - 1, items.get(items.size() - 1) + " " + t);
                    } else {
                        break;
                    }
                    j++;
                }
                host.addView(list(ctx, items));
                i = j;
                continue;
            }
            // General paragraph: continuous non-empty, non-special line consolidation
            List<String> para = new ArrayList<>();
            int j = i;
            while (j < lines.length) {
                String t = lines[j].trim();
                if (t.isEmpty() || isSpecialStart(lines, j)) {
                    break;
                }
                para.add(t);
                j++;
            }
            if (para.isEmpty()) {
                para.add(trimmed);
                j = i + 1;
            }
            host.addView(paragraph(ctx, join(para)));
            i = j;
        }
    }

    private static boolean isSpecialStart(String[] lines, int idx) {
        String t = lines[idx].trim();
        if (isDivider(t) || t.startsWith(">") || headingLevel(t) > 0 || isListLine(t)) {
            return true;
        }
        return t.indexOf('|') >= 0 && idx + 1 < lines.length && isTableDivider(lines[idx + 1]);
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(p);
        }
        return sb.toString();
    }

    private static boolean isDivider(String t) {
        if (t.length() < 3) {
            return false;
        }
        char c = t.charAt(0);
        if (c != '-' && c != '*' && c != '_') {
            return false;
        }
        for (int k = 0; k < t.length(); k++) {
            if (t.charAt(k) != c) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTableDivider(String line) {
        String t = line.trim();
        if (t.indexOf('-') < 0) {
            return false;
        }
        for (int k = 0; k < t.length(); k++) {
            char c = t.charAt(k);
            if (c != '-' && c != ':' && c != '|' && c != ' ' && c != '\t') {
                return false;
            }
        }
        return true;
    }

    private static int headingLevel(String t) {
        int n = 0;
        while (n < t.length() && t.charAt(n) == '#') {
            n++;
        }
        if (n >= 1 && n <= 6 && n < t.length() && t.charAt(n) == ' ') {
            return n;
        }
        return 0;
    }

    private static boolean isListLine(String t) {
        if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ")) {
            return true;
        }
        int k = 0;
        while (k < t.length() && Character.isDigit(t.charAt(k))) {
            k++;
        }
        return k > 0 && k + 1 < t.length()
                && (t.charAt(k) == '.' || t.charAt(k) == ')') && t.charAt(k + 1) == ' ';
    }

    // ---------- View Build ----------

    private static int dp(Context ctx, float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    private static android.graphics.drawable.Drawable roundBg(Context ctx, int color, int radius) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private static TextView baseText(Context ctx) {
        TextView tv = new TextView(ctx);
        tv.setTextColor(MainActivity.TEXT);
        tv.setTextSize(15f);
        tv.setLineSpacing(dp(ctx, 3), 1f);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    private static LinearLayout.LayoutParams lp(Context ctx, int topDp, int bottomDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(ctx, topDp);
        p.bottomMargin = dp(ctx, bottomDp);
        return p;
    }

    private static View paragraph(Context ctx, String text) {
        TextView tv = baseText(ctx);
        tv.setText(inline(text));
        tv.setLayoutParams(lp(ctx, 2, 2));
        return tv;
    }

    private static View heading(Context ctx, String text, int level) {
        TextView tv = baseText(ctx);
        float size = level == 1 ? 20f : level == 2 ? 18f : level == 3 ? 16.5f : 15.5f;
        tv.setTextSize(size);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(level <= 2 ? MainActivity.ACCENT : MainActivity.TEXT);
        tv.setText(inline(text));
        tv.setLayoutParams(lp(ctx, level <= 2 ? 12 : 8, 4));
        return tv;
    }

    private static View divider(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(MainActivity.DIVIDER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 0.7f)));
        p.topMargin = dp(ctx, 10);
        p.bottomMargin = dp(ctx, 10);
        v.setLayoutParams(p);
        return v;
    }

    private static View quote(Context ctx, String text) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(lp(ctx, 6, 6));

        View bar = new View(ctx);
        bar.setBackgroundColor(MainActivity.DIM);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(ctx, 3),
                ViewGroup.LayoutParams.MATCH_PARENT);
        bp.rightMargin = dp(ctx, 10);
        bar.setLayoutParams(bp);
        row.addView(bar);

        TextView tv = baseText(ctx);
        tv.setTextColor(MainActivity.MUTED);
        tv.setText(inline(text));
        tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);
        return row;
    }

    private static View list(Context ctx, List<String> items) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(lp(ctx, 4, 4));
        for (String raw : items) {
            String t = raw;
            String marker;
            int indent = 0;
            while (t.startsWith("  ")) {
                indent++;
                t = t.substring(2);
            }
            if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ")) {
                marker = indent == 0 ? "•" : "◦";
                t = t.substring(2);
            } else {
                int k = 0;
                while (k < t.length() && Character.isDigit(t.charAt(k))) {
                    k++;
                }
                marker = t.substring(0, k) + ".";
                t = t.substring(k + 1);
            }
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = dp(ctx, 2);
            rp.bottomMargin = dp(ctx, 2);
            rp.leftMargin = dp(ctx, 12 * indent);
            row.setLayoutParams(rp);

            TextView dot = baseText(ctx);
            dot.setText(marker);
            dot.setTextColor(MainActivity.MUTED);
            dot.setMinWidth(dp(ctx, 18));
            dot.setGravity(Gravity.TOP | Gravity.START);
            row.addView(dot);

            TextView tv = baseText(ctx);
            tv.setText(inline(t.trim()));
            tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tv);
            box.addView(row);
        }
        return box;
    }

    private static View table(Context ctx, List<String> block) {
        List<String[]> rows = new ArrayList<>();
        for (String line : block) {
            if (isTableDivider(line)) {
                continue;
            }
            rows.add(splitRow(line));
        }
        int cols = 0;
        for (String[] r : rows) {
            cols = Math.max(cols, r.length);
        }
        if (cols == 0) {
            return paragraph(ctx, join(block));
        }

        LinearLayout table = new LinearLayout(ctx);
        table.setOrientation(LinearLayout.VERTICAL);
        table.setBackground(roundBg(ctx, MainActivity.CARD_SOFT, dp(ctx, 10)));
        table.setPadding(dp(ctx, 1), dp(ctx, 1), dp(ctx, 1), dp(ctx, 1));

        for (int r = 0; r < rows.size(); r++) {
            String[] cells = rows.get(r);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackgroundColor(r == 0 ? MainActivity.CARD : MainActivity.BG);
            for (int c = 0; c < cols; c++) {
                String cell = c < cells.length ? cells[c] : "";
                TextView tv = baseText(ctx);
                tv.setTextSize(13.5f);
                tv.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));
                if (r == 0) {
                    tv.setTypeface(Typeface.DEFAULT_BOLD);
                    tv.setTextColor(MainActivity.ACCENT);
                }
                tv.setText(inline(cell));
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (c > 0) {
                    cp.leftMargin = dp(ctx, 1);
                }
                tv.setLayoutParams(cp);
                row.addView(tv);
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (r > 0) {
                rp.topMargin = dp(ctx, 1);
            }
            row.setLayoutParams(rp);
            table.addView(row);
        }

        HorizontalScrollView scroll = new HorizontalScrollView(ctx);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setLayoutParams(lp(ctx, 8, 8));
        scroll.addView(table, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private static String[] splitRow(String line) {
        String t = line.trim();
        if (t.startsWith("|")) {
            t = t.substring(1);
        }
        if (t.endsWith("|")) {
            t = t.substring(0, t.length() - 1);
        }
        String[] parts = t.split("\\|", -1);
        for (int k = 0; k < parts.length; k++) {
            parts[k] = parts[k].trim();
        }
        return parts;
    }

    // ---------- Inline Styles ----------

    static CharSequence inline(String src) {
        if (src == null || src.isEmpty()) {
            return "";
        }
        SpannableStringBuilder out = new SpannableStringBuilder();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '`') {
                int end = src.indexOf('`', i + 1);
                if (end > i) {
                    int start = out.length();
                    out.append(src, i + 1, end);
                    out.setSpan(new TypefaceSpan("monospace"), start, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(Color.rgb(38, 38, 38)), start, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new ForegroundColorSpan(Color.rgb(232, 232, 232)), start, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            if (c == '*' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("**", i + 2);
                if (end > i) {
                    int start = out.length();
                    out.append(src, i + 2, end);
                    out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            if (c == '[') {
                int close = src.indexOf(']', i + 1);
                if (close > i && close + 1 < n && src.charAt(close + 1) == '(') {
                    int paren = src.indexOf(')', close + 2);
                    if (paren > close) {
                        int start = out.length();
                        out.append(src, i + 1, close);
                        out.setSpan(new ForegroundColorSpan(Color.rgb(140, 190, 255)), start,
                                out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        i = paren + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out;
    }
}
