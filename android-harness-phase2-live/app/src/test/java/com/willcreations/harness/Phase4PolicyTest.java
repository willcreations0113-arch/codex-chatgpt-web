package com.willcreations.harness;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Phase4PolicyTest {
    private final AgentPolicy policy = new AgentPolicy();
    private final AgentVerifier verifier = new AgentVerifier();

    @Test
    public void visibleSafeClickIsAllowed() {
        UiSnapshot snapshot = new UiSnapshot(
                "com.android.settings",
                "#1 class=Button text=\"Bluetooth\" desc=\"\" clickable=true enabled=true\n",
                true);
        AgentAction action = new AgentAction(AgentAction.Type.CLICK_TEXT, "Bluetooth", "open it", "");
        assertTrue(policy.check(action, snapshot).allowed);
    }

    @Test
    public void destructiveClickIsDenied() {
        UiSnapshot snapshot = new UiSnapshot(
                "com.android.settings",
                "#1 class=Button text=\"端末を初期化\" desc=\"\" clickable=true enabled=true\n",
                true);
        AgentAction action = new AgentAction(AgentAction.Type.CLICK_TEXT, "端末を初期化", "", "");
        assertFalse(policy.check(action, snapshot).allowed);
    }

    @Test
    public void invisibleClickIsDenied() {
        UiSnapshot snapshot = new UiSnapshot("com.android.settings", "text=\"Bluetooth\"", true);
        AgentAction action = new AgentAction(AgentAction.Type.CLICK_TEXT, "Wi-Fi", "", "");
        assertFalse(policy.check(action, snapshot).allowed);
    }

    @Test
    public void finishRequiresVisibleEvidence() {
        UiSnapshot snapshot = new UiSnapshot("com.android.settings", "text=\"Bluetooth\"", true);
        AgentAction good = new AgentAction(AgentAction.Type.FINISH, "Bluetooth", "done", "done");
        AgentAction bad = new AgentAction(AgentAction.Type.FINISH, "Connected devices", "done", "done");
        assertTrue(verifier.verifyFinish(good, snapshot).passed);
        assertFalse(verifier.verifyFinish(bad, snapshot).passed);
    }

    @Test
    public void thirdPartyPackageIsDenied() {
        UiSnapshot snapshot = new UiSnapshot("com.twitter.android", "text=\"Bluetooth\"", false);
        AgentAction action = new AgentAction(AgentAction.Type.CLICK_TEXT, "Bluetooth", "", "");
        assertFalse(policy.check(action, snapshot).allowed);
    }
}
