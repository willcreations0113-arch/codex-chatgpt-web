package com.willcreations.harness;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

public final class ToolRegistry {
    private final Context context;
    private final ShizukuBridge shizuku;

    public ToolRegistry(Context context, ShizukuBridge shizuku) {
        this.context = context.getApplicationContext();
        this.shizuku = shizuku;
    }

    public ToolResult execute(AgentAction action) {
        if (action == null) return ToolResult.fail("No action");
        try {
            switch (action.type) {
                case OPEN_BLUETOOTH_SETTINGS:
                    Intent bluetooth = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(bluetooth);
                    return ToolResult.ok("Opened Bluetooth settings");
                case CLICK_TEXT:
                    return HarnessAccessibilityService.clickText(action.targetText);
                case BACK:
                    return HarnessAccessibilityService.goBack();
                case READ_PACKAGES:
                    return shizuku == null
                            ? ToolResult.fail("Shizuku bridge unavailable")
                            : shizuku.agentListPackages();
                case READ_PROCESSES:
                    return shizuku == null
                            ? ToolResult.fail("Shizuku bridge unavailable")
                            : shizuku.agentListProcesses();
                case WAIT:
                    return ToolResult.ok("WAIT");
                case FINISH:
                    return ToolResult.ok("FINISH requested");
                case FAIL:
                    return ToolResult.fail(action.message.isEmpty() ? "Planner reported failure" : action.message);
                default:
                    return ToolResult.fail("Unsupported action: " + action.type);
            }
        } catch (Throwable t) {
            return ToolResult.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
