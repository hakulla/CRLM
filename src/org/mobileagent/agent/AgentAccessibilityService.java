package org.mobileagent.agent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Locale;

public class AgentAccessibilityService extends AccessibilityService {
    private static volatile AgentAccessibilityService instance;

    static AgentAccessibilityService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    String dumpUi() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            throw new IllegalStateException("Could not read current window (possibly security interface or interface not ready)");
        }
        StringBuilder builder = new StringBuilder();
        builder.append("package: ").append(root.getPackageName()).append('\n');
        int[] count = new int[]{0};
        dumpNode(root, 0, builder, count);
        root.recycle();
        if (count[0] >= 160) {
            builder.append("...[Too many interface nodes. Interrupted]\n");
        }
        return builder.toString();
    }

    String currentPackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return "";
        }
        try {
            CharSequence name = root.getPackageName();
            return name == null ? "" : name.toString();
        } finally {
            root.recycle();
        }
    }

    /** Text-only walk for browser pages; Chrome's DOM can exceed the normal UI dump cap. */

    /** Browser text can be many screens off-screen; collect the whole tree, not only the viewport. */
    String dumpAllText() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            throw new IllegalStateException("Could not read current window");
        }
        StringBuilder builder = new StringBuilder();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        int[] count = new int[]{0};
        collectAllText(root, builder, seen, count);
        root.recycle();
        return builder.toString();
    }

    private void collectAllText(AccessibilityNodeInfo node, StringBuilder builder,
                                java.util.Set<String> seen, int[] count) {
        if (node == null || count[0] >= 4000 || builder.length() > 50000) {
            return;
        }
        try {
            count[0]++;
            String text = safe(node.getText()).trim();
            String desc = safe(node.getContentDescription()).trim();
            if (!text.isEmpty() && seen.add(text)) {
                builder.append(text).append('\n');
            }
            if (!desc.isEmpty() && !desc.equals(text) && seen.add(desc)) {
                builder.append(desc).append('\n');
            }
            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    collectAllText(child, builder, seen, count);
                    child.recycle();
                }
            }
        } catch (RuntimeException ignored) {
        }
    }


    private void dumpNode(AccessibilityNodeInfo node, int depth, StringBuilder builder, int[] count) {
        if (node == null || count[0] >= 160 || builder.length() > 13000) {
            return;
        }
        try {
            if (!node.isVisibleToUser()) {
                return;
            }
            count[0]++;
            for (int i = 0; i < depth; i++) {
                builder.append("  ");
            }
            builder.append('[').append(count[0]).append("] ");
            CharSequence className = node.getClassName();
            builder.append(className == null ? "View" : className.toString());
            String text = node.isPassword() ? "••••" : safe(node.getText());
            String desc = safe(node.getContentDescription());
            String hint = hintOf(node);
            String id = node.getViewIdResourceName();
            if (!text.isEmpty()) {
                builder.append(" text=\"").append(escape(text)).append('"');
            }
            if (!desc.isEmpty()) {
                builder.append(" desc=\"").append(escape(desc)).append('"');
            }
            if (!hint.isEmpty()) {
                builder.append(" hint=\"").append(escape(hint)).append('"');
            }
            if (id != null && !id.isEmpty()) {
                builder.append(" id=").append(id);
            }
            if (node.isClickable()) {
                builder.append(" clickable=true");
            }
            if (node.isEditable()) {
                builder.append(" editable=true");
            }
            if (node.isScrollable()) {
                builder.append(" scrollable=true");
            }
            if (node.isCheckable()) {
                builder.append(" checked=").append(node.isChecked());
            }
            if (!node.isEnabled()) {
                builder.append(" enabled=false");
            }
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            builder.append(" bounds=[")
                    .append(bounds.left).append(',').append(bounds.top).append("][")
                    .append(bounds.right).append(',').append(bounds.bottom).append("]\n");

            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    dumpNode(child, depth + 1, builder, count);
                    child.recycle();
                }
            }
        } catch (RuntimeException ignored) {
        }
    }

    void tapByText(String target) {
        AccessibilityNodeInfo node = findNode(target, false);
        if (node == null) {
            throw new IllegalStateException("No text found on interface:" + target);
        }
        AccessibilityNodeInfo clickable = node;
        int climbs = 0;
        while (clickable != null && !clickable.isClickable() && climbs < 7) {
            clickable = clickable.getParent();
            climbs++;
        }
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() > 0 && bounds.height() > 0
                && tapAtInternal(bounds.centerX(), bounds.centerY())) {
            return;
        }
        throw new IllegalStateException("Could not close temporary folder: %s" + target);
    }

    void tapAt(int x, int y) {
        if (!tapAtInternal(x, y)) {
            throw new IllegalStateException("Clicking on coordinates failed");
        }
    }

    void setText(String target, String text) {
        AccessibilityNodeInfo node = findNode(target, true);
        if (node == null) {
            throw new IllegalStateException("No input boxes found on interface:" + target);
        }
        if (!node.isEditable()) {
            AccessibilityNodeInfo descendant = findEditableDescendant(node);
            if (descendant != null) {
                node = descendant;
            }
        }
        if (!node.isEditable()) {
            AccessibilityNodeInfo parent = node;
            int climbs = 0;
            while (parent != null && !parent.isEditable() && climbs < 5) {
                parent = parent.getParent();
                climbs++;
            }
            if (parent != null && parent.isEditable()) {
                node = parent;
            }
        }
        if (!node.isEditable()) {
            throw new IllegalStateException("The control matching is not an input box:" + target);
        }
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle arguments = new Bundle();
        arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            throw new IllegalStateException("Input failed:" + target);
        }
    }

    private AccessibilityNodeInfo findEditableDescendant(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < 200) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;
            try {
                if (node.isEditable() && node.isVisibleToUser()) {
                    return node;
                }
                int count = node.getChildCount();
                for (int i = 0; i < count; i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        queue.add(child);
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    boolean isInstallerPackage(String packageName) {
        String value = packageName == null ? "" : packageName.toLowerCase(Locale.ROOT);
        return value.equals("com.android.packageinstaller")
                || value.contains("packageinstaller")
                || value.equals("com.huawei.appmarket")
                || value.startsWith("com.huawei.appmarket:")
                || value.startsWith("com.huawei.appgallery")
                || value.startsWith("com.huawei.android.packageinstaller");
    }

    /**
     * Best-effort automation of the system installer only. Never call this for arbitrary apps.
     * Returns true when it filled a password or clicked an installer button.
     */
    boolean assistInstaller(String secret, StringBuilder note) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return false;
        }
        try {
            String pkg = safe(root.getPackageName()).toLowerCase(Locale.ROOT);
            if (!isInstallerPackage(pkg)) {
                return false;
            }
            boolean acted = false;
            AccessibilityNodeInfo password = findPasswordField(root);
            if (password != null) {
                try {
                    if (secret != null && !secret.isEmpty()) {
                        password.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        password.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                        Bundle arguments = new Bundle();
                        arguments.putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                secret);
                        if (password.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                            acted = true;
                            note.append("password_filled;");
                        } else {
                            note.append("password_blocked;");
                        }
                    }
                } catch (RuntimeException ignored) {
                } finally {
                    password.recycle();
                }
            }
            if (clickInstallerButton(root, pkg, note)) {
                acted = true;
            }
            return acted;
        } finally {
            root.recycle();
        }
    }

    private AccessibilityNodeInfo findPasswordField(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < 400) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;
            try {
                if (node.isVisibleToUser() && node.isEnabled() && node.isEditable()) {
                    String hint = hintOf(node).toLowerCase(Locale.ROOT);
                    String id = node.getViewIdResourceName() == null
                            ? "" : node.getViewIdResourceName().toLowerCase(Locale.ROOT);
                    if (node.isPassword()
                            || hint.contains("Password") || hint.contains("password")
                            || hint.contains("pin") || hint.contains("Lock Screen")
                            || id.contains("password")) {
                        return node;
                    }
                }
                int count = node.getChildCount();
                for (int i = 0; i < count; i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        queue.add(child);
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    private boolean clickInstallerButton(AccessibilityNodeInfo root, String packageName,
                                         StringBuilder note) {
        boolean appMarket = packageName.contains("appmarket")
                || packageName.contains("appgallery");
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < 400) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;
            try {
                if (!node.isVisibleToUser() || !node.isEnabled()) {
                    continue;
                }
                String text = safe(node.getText()).replace(" ", "").trim();
                String desc = safe(node.getContentDescription()).replace(" ", "").trim();
                String id = node.getViewIdResourceName() == null
                        ? "" : node.getViewIdResourceName().toLowerCase(Locale.ROOT);
                String label = !text.isEmpty() ? text : desc;
                boolean safeLabel;
                if (appMarket) {
                    safeLabel = label.equals("Continue installation")
                            || id.contains("install_button_continue");
                } else {
                    safeLabel = label.equals("Install") || label.equals("Continue installation")
                            || label.equals("Sure.") || label.equals("Allow")
                            || label.equals("Next") || label.equals("Confirm.");
                }
                if (!safeLabel || label.equals("Cancel") || label.equals("Open")) {
                    continue;
                }
                AccessibilityNodeInfo clickable = node;
                int climbs = 0;
                while (clickable != null && !clickable.isClickable() && climbs < 5) {
                    clickable = clickable.getParent();
                    climbs++;
                }
                if (clickable != null
                        && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    note.append("clicked=").append(label).append(';');
                    return true;
                }
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (bounds.width() > 0 && bounds.height() > 0
                        && tapAtInternal(bounds.centerX(), bounds.centerY())) {
                    note.append("clicked=").append(label).append(';');
                    return true;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return false;
    }

    void swipe(String direction) {
        String value = direction == null ? "" : direction.trim().toLowerCase(Locale.ROOT);
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        int cx = width / 2;
        int cy = height / 2;
        int dx = width / 4;
        int dy = height / 4;
        Path path = new Path();
        switch (value) {
            case "up":
                path.moveTo(cx, cy + dy);
                path.lineTo(cx, cy - dy);
                break;
            case "down":
                path.moveTo(cx, cy - dy);
                path.lineTo(cx, cy + dy);
                break;
            case "left":
                path.moveTo(cx + dx, cy);
                path.lineTo(cx - dx, cy);
                break;
            case "right":
                path.moveTo(cx - dx, cy);
                path.lineTo(cx + dx, cy);
                break;
            default:
                throw new IllegalArgumentException("The slide must be on. up/down/left/right");
        }
        if (!gesture(path, 320)) {
            throw new IllegalStateException("Slide failed");
        }
    }

    void globalAction(String action) {
        String value = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        int globalAction;
        switch (value) {
            case "back":
                globalAction = GLOBAL_ACTION_BACK;
                break;
            case "home":
                globalAction = GLOBAL_ACTION_HOME;
                break;
            case "recents":
                globalAction = GLOBAL_ACTION_RECENTS;
                break;
            default:
                throw new IllegalArgumentException("The system operation must be back/home/recents");
        }
        if (!performGlobalAction(globalAction)) {
            throw new IllegalStateException("System operation failed:" + value);
        }
    }

    private boolean tapAtInternal(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        return gesture(path, 60);
    }

    private boolean gesture(Path path, long durationMs) {
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs));
        return dispatchGesture(builder.build(), null, null);
    }

    private AccessibilityNodeInfo findNode(String target, boolean editablePreferred) {
        if (target == null || target.trim().isEmpty()) {
            return null;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            throw new IllegalStateException("Could not read current window");
        }
        String needle = target.trim().toLowerCase(Locale.ROOT);
        AccessibilityNodeInfo best = null;
        int bestScore = -1;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < 500) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;
            try {
                if (!node.isVisibleToUser()) {
                    continue;
                }
                String text = safe(node.getText());
                String desc = safe(node.getContentDescription());
                String hint = hintOf(node);
                String id = node.getViewIdResourceName() == null
                        ? "" : node.getViewIdResourceName();
                int score = -1;
                if (equalsIgnoreCase(text, needle) || equalsIgnoreCase(desc, needle)
                        || equalsIgnoreCase(hint, needle)) {
                    score = 120;
                } else if (equalsIgnoreCase(id, needle)) {
                    score = 100;
                } else if (containsIgnoreCase(text, needle)
                        || containsIgnoreCase(desc, needle)
                        || containsIgnoreCase(hint, needle)
                        || containsIgnoreCase(id, needle)) {
                    score = 60;
                }
                if (score >= 0) {
                    if (editablePreferred) {
                        score += node.isEditable() ? 40 : -25;
                    } else if (node.isClickable()) {
                        score += 15;
                    }
                    if (score > bestScore) {
                        bestScore = score;
                        best = node;
                    }
                }
                int count = node.getChildCount();
                for (int i = 0; i < count; i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        queue.add(child);
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        return best;
    }

    private static String safe(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private static String hintOf(AccessibilityNodeInfo node) {
        if (Build.VERSION.SDK_INT >= 26) {
            return safe(node.getHintText());
        }
        return "";
    }

    private static boolean equalsIgnoreCase(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).equals(needle);
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\"", "\\\"");
    }
}
