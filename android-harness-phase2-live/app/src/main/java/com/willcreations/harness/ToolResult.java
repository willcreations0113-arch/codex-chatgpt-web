package com.willcreations.harness;

public final class ToolResult {
    public final boolean ok;
    public final String message;

    private ToolResult(boolean ok, String message) {
        this.ok = ok;
        this.message = message == null ? "" : message;
    }

    public static ToolResult ok(String message) { return new ToolResult(true, message); }
    public static ToolResult fail(String message) { return new ToolResult(false, message); }
}
