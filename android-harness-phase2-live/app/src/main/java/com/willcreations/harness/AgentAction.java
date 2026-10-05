package com.willcreations.harness;

import org.json.JSONException;
import org.json.JSONObject;

public final class AgentAction {
    public enum Type {
        OPEN_BLUETOOTH_SETTINGS,
        CLICK_TEXT,
        BACK,
        READ_PACKAGES,
        READ_PROCESSES,
        WAIT,
        FINISH,
        FAIL
    }

    public final Type type;
    public final String targetText;
    public final String rationale;
    public final String message;

    public AgentAction(Type type, String targetText, String rationale, String message) {
        this.type = type;
        this.targetText = targetText == null ? "" : targetText;
        this.rationale = rationale == null ? "" : rationale;
        this.message = message == null ? "" : message;
    }

    public static AgentAction fromJson(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        Type type = Type.valueOf(o.getString("action"));
        return new AgentAction(
                type,
                o.optString("target_text", ""),
                o.optString("rationale", ""),
                o.optString("message", "")
        );
    }

    @Override
    public String toString() {
        return type + (targetText.isEmpty() ? "" : " target=\"" + targetText + "\"");
    }
}
