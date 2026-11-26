package io.codetriage.llm;

public interface LlmClient {

    /**
     * One completion. Implementations must not retry forever: the caller
     * generates a corpus of dozens of these and needs a bounded failure.
     */
    String complete(String systemPrompt, String userPrompt, double temperature);

    boolean isConfigured();

    String modelName();
}
