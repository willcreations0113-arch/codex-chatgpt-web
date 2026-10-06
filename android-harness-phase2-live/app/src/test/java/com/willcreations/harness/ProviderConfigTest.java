package com.willcreations.harness;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProviderConfigTest {
    @Test public void allProviderDefaultsAreComplete() {
        for (ProviderConfig.Type type : ProviderConfig.Type.values()) {
            ProviderConfig c = ProviderConfig.defaults(type);
            assertEquals(type, c.type);
            assertFalse(c.baseUrl.isEmpty());
            if (type != ProviderConfig.Type.CUSTOM_RESPONSES) assertFalse(c.model.isEmpty());
        }
    }

    @Test public void knownPresetsUseExpectedApiFamilies() {
        assertEquals("https://api.openai.com/v1", ProviderConfig.defaults(ProviderConfig.Type.CHATGPT_LOGIN).baseUrl);
        assertEquals("https://api.openai.com/v1", ProviderConfig.defaults(ProviderConfig.Type.OPENAI).baseUrl);
        assertEquals("https://api.anthropic.com", ProviderConfig.defaults(ProviderConfig.Type.ANTHROPIC).baseUrl);
        assertEquals("https://generativelanguage.googleapis.com/v1", ProviderConfig.defaults(ProviderConfig.Type.GEMINI).baseUrl);
        assertEquals("https://api.x.ai/v1", ProviderConfig.defaults(ProviderConfig.Type.XAI).baseUrl);
        assertEquals("https://api.groq.com/openai/v1", ProviderConfig.defaults(ProviderConfig.Type.GROQ).baseUrl);
        assertEquals("https://openrouter.ai/api/v1", ProviderConfig.defaults(ProviderConfig.Type.OPENROUTER).baseUrl);
    }

    @Test public void chatGptLoginIsNotApiKeyMode() {
        ProviderConfig c = ProviderConfig.defaults(ProviderConfig.Type.CHATGPT_LOGIN);
        assertTrue(c.usesChatGptLogin());
        assertFalse(c.requiresApiKey());
    }

    @Test(expected = IllegalArgumentException.class)
    public void chatGptLoginRejectsNonOpenAiApiDestination() {
        new ProviderConfig(
                ProviderConfig.Type.CHATGPT_LOGIN,
                "gpt-6.1-sol",
                "https://example.com/v1"
        ).validate();
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonHttpsRemoteBaseUrl() {
        new ProviderConfig(ProviderConfig.Type.CUSTOM_RESPONSES, "model", "http://example.com/v1").validate();
    }

    @Test public void trimsTrailingSlash() {
        ProviderConfig c = new ProviderConfig(ProviderConfig.Type.OPENAI, "m", "https://example.com/v1///");
        assertEquals("https://example.com/v1", c.baseUrl);
    }
}
