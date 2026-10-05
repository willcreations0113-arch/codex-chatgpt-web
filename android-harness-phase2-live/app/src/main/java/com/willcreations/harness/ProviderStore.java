package com.willcreations.harness;

import android.content.Context;
import android.content.SharedPreferences;

public final class ProviderStore {
    private static final String PREFS = "ai_provider_config";
    private static final String SELECTED = "selected_provider";
    private final SharedPreferences prefs;

    public ProviderStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public ProviderConfig.Type selectedType() {
        String value = prefs.getString(SELECTED, ProviderConfig.Type.OPENAI.name());
        try { return ProviderConfig.Type.valueOf(value); }
        catch (Exception e) { return ProviderConfig.Type.OPENAI; }
    }

    public ProviderConfig load(ProviderConfig.Type type) {
        ProviderConfig d = ProviderConfig.defaults(type);
        return new ProviderConfig(
                type,
                prefs.getString(key(type, "model"), d.model),
                prefs.getString(key(type, "base"), d.baseUrl)
        );
    }

    public void save(ProviderConfig config) {
        prefs.edit()
                .putString(SELECTED, config.type.name())
                .putString(key(config.type, "model"), config.model)
                .putString(key(config.type, "base"), config.baseUrl)
                .apply();
    }

    public void select(ProviderConfig.Type type) {
        prefs.edit().putString(SELECTED, type.name()).apply();
    }

    private String key(ProviderConfig.Type type, String suffix) {
        return type.name().toLowerCase() + "_" + suffix;
    }
}
