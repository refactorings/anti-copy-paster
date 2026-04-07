package org.jetbrains.research.anticopypaster.llm;

public interface LlmClient {
    String complete(String prompt) throws Exception;
}