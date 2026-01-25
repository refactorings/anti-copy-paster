package org.jetbrains.research.anticopypaster.llm;

public final class NoopLlmClient implements LlmClient {
    @Override public String complete(String prompt) { return ""; }
}