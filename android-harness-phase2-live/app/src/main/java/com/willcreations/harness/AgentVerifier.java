package com.willcreations.harness;

public final class AgentVerifier {
    public Verification verifyFinish(AgentAction action, UiSnapshot snapshot) {
        if (action == null || action.type != AgentAction.Type.FINISH) {
            return Verification.fail("Not a FINISH action");
        }
        if (snapshot == null || !snapshot.allowed) {
            return Verification.fail("Current UI is not in the allowlist");
        }
        String evidence = action.targetText == null ? "" : action.targetText.trim();
        if (evidence.isEmpty()) {
            return Verification.fail("FINISH requires visible target_text as evidence");
        }
        if (!snapshot.containsIgnoreCase(evidence)) {
            return Verification.fail("Finish evidence is not visible: " + evidence);
        }
        return Verification.pass("Verified visible evidence: " + evidence);
    }

    public static final class Verification {
        public final boolean passed;
        public final String message;

        private Verification(boolean passed, String message) {
            this.passed = passed;
            this.message = message;
        }

        public static Verification pass(String message) { return new Verification(true, message); }
        public static Verification fail(String message) { return new Verification(false, message); }
    }
}
