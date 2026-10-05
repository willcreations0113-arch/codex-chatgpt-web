package com.willcreations.harness;

public final class ProviderConfig {
    public enum Type {
        OPENAI("OpenAI"),
        ANTHROPIC("Anthropic Claude"),
        GEMINI("Google Gemini"),
        XAI("xAI Grok"),
        GROQ("Groq"),
        OPENROUTER("OpenRouter"),
        CUSTOM_RESPONSES("Custom OpenAI-compatible");

        public final String label;
        Type(String label) { this.label = label; }

        public static Type fromLabel(String label) {
            for (Type t : values()) if (t.label.equals(label)) return t;
            return OPENAI;
        }
    }

    public final Type type;
    public final String model;
    public final String baseUrl;

    public ProviderConfig(Type type, String model, String baseUrl) {
        this.type = type == null ? Type.OPENAI : type;
        this.model = model == null ? "" : model.trim();
        this.baseUrl = trimSlash(baseUrl == null ? "" : baseUrl.trim());
    }

    public static ProviderConfig defaults(Type type) {
        switch (type) {
            case ANTHROPIC:
                return new ProviderConfig(type, "claude-sonnet-5-5", "https://api.anthropic.com");
            case GEMINI:
                return new ProviderConfig(type, "gemini-3.8-flash", "https://generativelanguage.googleapis.com/v1");
            case XAI:
                return new ProviderConfig(type, "grok-4.7", "https://api.x.ai/v1");
            case GROQ:
                return new ProviderConfig(type, "openai/gpt-oss-20b", "https://api.groq.com/openai/v1");
            case OPENROUTER:
                return new ProviderConfig(type, "openrouter/auto", "https://openrouter.ai/api/v1");
            case CUSTOM_RESPONSES:
                return new ProviderConfig(type, "", "https://example.com/v1");
            case OPENAI:
            default:
                return new ProviderConfig(Type.OPENAI, "gpt-5.6-terra", "https://api.openai.com/v1");
        }
    }

    public String id() { return type.name().toLowerCase(); }

    public void validate() {
        if (model.isEmpty()) throw new IllegalArgumentException("Model is empty");
        if (baseUrl.isEmpty()) throw new IllegalArgumentException("Base URL is empty");
        if (!(baseUrl.startsWith("https://") || baseUrl.startsWith("http://127.0.0.1") || baseUrl.startsWith("http://localhost"))) {
            throw new IllegalArgumentException("Base URL must use HTTPS (localhost is allowed)");
        }
    }

    private static String trimSlash(String value) {
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }
}
