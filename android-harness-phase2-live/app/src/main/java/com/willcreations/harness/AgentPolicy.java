package com.willcreations.harness;

import java.util.Locale;
import java.util.Set;

public final class AgentPolicy {
    private static final Set<String> ALLOWED_PACKAGES = Set.of(
            "com.willcreations.harness",
            "com.android.settings"
    );

    private static final String[] DENIED_TARGETS = {
            "削除", "消去", "初期化", "リセット", "アンインストール", "工場出荷",
            "delete", "erase", "factory reset", "reset", "uninstall", "wipe"
    };

    public Decision check(AgentAction action, UiSnapshot snapshot) {
        if (action == null) return Decision.deny("No action");
        if (snapshot == null) return Decision.deny("No UI snapshot");
        if (!snapshot.packageName.isEmpty() && !ALLOWED_PACKAGES.contains(snapshot.packageName)) {
            return Decision.deny("Package is outside Phase 4 allowlist: " + snapshot.packageName);
        }

        switch (action.type) {
            case OPEN_BLUETOOTH_SETTINGS:
            case BACK:
            case READ_PACKAGES:
            case READ_PROCESSES:
            case WAIT:
            case FINISH:
            case FAIL:
                return Decision.allow();
            case CLICK_TEXT:
                String target = action.targetText == null ? "" : action.targetText.trim();
                if (target.isEmpty()) return Decision.deny("CLICK_TEXT requires target_text");
                if (target.length() > 100) return Decision.deny("Click target is too long");
                String lower = target.toLowerCase(Locale.ROOT);
                for (String denied : DENIED_TARGETS) {
                    if (lower.contains(denied.toLowerCase(Locale.ROOT))) {
                        return Decision.deny("Destructive click blocked: " + target);
                    }
                }
                if (!snapshot.containsIgnoreCase(target)) {
                    return Decision.deny("Target is not visible in current UI: " + target);
                }
                return Decision.allow();
            default:
                return Decision.deny("Unknown action");
        }
    }

    public static final class Decision {
        public final boolean allowed;
        public final String reason;

        private Decision(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        public static Decision allow() { return new Decision(true, "allowed"); }
        public static Decision deny(String reason) { return new Decision(false, reason); }
    }
}
