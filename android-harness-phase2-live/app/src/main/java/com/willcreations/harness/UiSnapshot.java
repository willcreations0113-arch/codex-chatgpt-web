package com.willcreations.harness;

public final class UiSnapshot {
    public final String packageName;
    public final String tree;
    public final boolean allowed;

    public UiSnapshot(String packageName, String tree, boolean allowed) {
        this.packageName = packageName == null ? "" : packageName;
        this.tree = tree == null ? "" : tree;
        this.allowed = allowed;
    }

    public boolean containsIgnoreCase(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        return tree.toLowerCase(java.util.Locale.ROOT)
                .contains(text.trim().toLowerCase(java.util.Locale.ROOT));
    }
}
