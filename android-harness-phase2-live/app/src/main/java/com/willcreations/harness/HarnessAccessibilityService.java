package com.willcreations.harness;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public class HarnessAccessibilityService extends AccessibilityService {
    private static final Set<String> ALLOWED_PACKAGES = Set.of(
            "com.willcreations.harness",
            "com.android.settings"
    );
    private static volatile HarnessAccessibilityService instance;

    private TaskStore store;
    private long lastClick = 0;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        instance = this;
        store = new TaskStore(this);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public static boolean isReady() {
        return instance != null;
    }

    public static UiSnapshot observeAllowedUi() {
        HarnessAccessibilityService s = instance;
        if (s == null) return new UiSnapshot("", "Accessibility service not connected", false);
        return s.callOnMain(s::snapshotOnMain,
                new UiSnapshot("", "Could not read current UI", false));
    }

    public static ToolResult clickText(String text) {
        HarnessAccessibilityService s = instance;
        if (s == null) return ToolResult.fail("Accessibility service not connected");
        return s.callOnMain(() -> s.clickTextOnMain(text), ToolResult.fail("UI action timed out"));
    }

    public static ToolResult goBack() {
        HarnessAccessibilityService s = instance;
        if (s == null) return ToolResult.fail("Accessibility service not connected");
        return s.callOnMain(() -> s.performGlobalAction(GLOBAL_ACTION_BACK)
                        ? ToolResult.ok("BACK") : ToolResult.fail("BACK failed"),
                ToolResult.fail("BACK timed out"));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (!ALLOWED_PACKAGES.contains(pkg)) return;

        // Keep the original deterministic Phase 2 acceptance test, but only inside this app.
        if (!"com.willcreations.harness".equals(pkg) || store == null) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        if (findExact(root, "完了") != null) {
            store.set("PASSED", store.attempt());
            return;
        }
        if (!"WAIT_TARGET".equals(store.state()) && !"VERIFYING".equals(store.state())
                && !"RECOVERY_PENDING".equals(store.state())) return;
        if (System.currentTimeMillis() - lastClick < 3000) return;
        AccessibilityNodeInfo n = findExact(root, "操作対象");
        if (n != null) {
            AccessibilityNodeInfo clickable = clickableAncestor(n);
            if (clickable == null) return;
            int a = store.attempt() + 1;
            if (a > 5) {
                store.set("FAILED", a);
                return;
            }
            if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                lastClick = System.currentTimeMillis();
                store.set("VERIFYING", a);
            }
        }
    }

    private UiSnapshot snapshotOnMain() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return new UiSnapshot("", "No active accessibility root", false);
        CharSequence p = root.getPackageName();
        String pkg = p == null ? "" : p.toString();
        if (!ALLOWED_PACKAGES.contains(pkg)) {
            return new UiSnapshot(pkg, "BLOCKED_PACKAGE: " + pkg, false);
        }
        StringBuilder out = new StringBuilder();
        int[] count = {0};
        appendNode(root, out, 0, count);
        return new UiSnapshot(pkg, out.toString(), true);
    }

    private void appendNode(AccessibilityNodeInfo n, StringBuilder out, int depth, int[] count) {
        if (n == null || count[0] >= 120 || out.length() >= 12000) return;
        count[0]++;
        String text = clean(n.getText());
        String desc = clean(n.getContentDescription());
        String clazz = clean(n.getClassName());
        if (!text.isEmpty() || !desc.isEmpty() || n.isClickable()) {
            out.append('#').append(count[0])
                    .append(" class=").append(clazz)
                    .append(" text=\"").append(text).append("\"")
                    .append(" desc=\"").append(desc).append("\"")
                    .append(" clickable=").append(n.isClickable())
                    .append(" enabled=").append(n.isEnabled())
                    .append('\n');
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            appendNode(n.getChild(i), out, depth + 1, count);
        }
    }

    private ToolResult clickTextOnMain(String query) {
        if (query == null || query.trim().isEmpty()) return ToolResult.fail("Empty click target");
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return ToolResult.fail("No active UI");
        String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
        if (!ALLOWED_PACKAGES.contains(pkg)) return ToolResult.fail("Package blocked: " + pkg);
        AccessibilityNodeInfo n = findContains(root, query.trim());
        if (n == null) return ToolResult.fail("Text not found: " + query);
        AccessibilityNodeInfo clickable = clickableAncestor(n);
        if (clickable == null) return ToolResult.fail("Text found but not clickable: " + query);
        return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ? ToolResult.ok("CLICKED: " + query)
                : ToolResult.fail("Click failed: " + query);
    }

    private AccessibilityNodeInfo findExact(AccessibilityNodeInfo n, String text) {
        if (n == null) return null;
        CharSequence t = n.getText();
        if (t != null && text.contentEquals(t)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = findExact(n.getChild(i), text);
            if (f != null) return f;
        }
        return null;
    }

    private AccessibilityNodeInfo findContains(AccessibilityNodeInfo n, String query) {
        if (n == null) return null;
        String q = query.toLowerCase(Locale.ROOT);
        String text = clean(n.getText()).toLowerCase(Locale.ROOT);
        String desc = clean(n.getContentDescription()).toLowerCase(Locale.ROOT);
        if ((!text.isEmpty() && text.contains(q)) || (!desc.isEmpty() && desc.contains(q))) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = findContains(n.getChild(i), query);
            if (f != null) return f;
        }
        return null;
    }

    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo current = n;
        for (int i = 0; current != null && i < 5; i++) {
            if (current.isClickable() && current.isEnabled()) return current;
            current = current.getParent();
        }
        return null;
    }

    private String clean(CharSequence value) {
        if (value == null) return "";
        return value.toString().replace('\n', ' ').replace('\r', ' ').replace('"', '\'').trim();
    }

    private <T> T callOnMain(Callable<T> callable, T fallback) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try { return callable.call(); } catch (Exception e) { return fallback; }
        }
        FutureTask<T> task = new FutureTask<>(callable);
        main.post(task);
        try { return task.get(3, TimeUnit.SECONDS); } catch (Exception e) { return fallback; }
    }

    @Override
    public void onInterrupt() {}
}
