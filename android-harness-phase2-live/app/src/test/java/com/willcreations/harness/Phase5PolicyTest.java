package com.willcreations.harness;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class Phase5PolicyTest {
    private final AgentPolicy policy = new AgentPolicy();
    private final UiSnapshot harness = new UiSnapshot(
            "com.willcreations.harness",
            "text=\"Phase 5\"",
            true
    );

    @Test public void fixedDeveloperCapabilitiesAreAllowed() {
        AgentAction.Type[] allowed = {
                AgentAction.Type.DEV_ENV_PROBE,
                AgentAction.Type.DEV_GIT_STATUS,
                AgentAction.Type.DEV_GIT_DIFF,
                AgentAction.Type.DEV_TESTS,
                AgentAction.Type.DEV_BUILD
        };
        for (AgentAction.Type type : allowed) {
            AgentAction action = new AgentAction(type, "", "phase5", "");
            assertTrue(type.name(), policy.check(action, harness).allowed);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void arbitraryShellActionDoesNotExist() {
        AgentAction.Type.valueOf("SHELL");
    }
}
