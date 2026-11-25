package dev.platform.reading.tuvi.application.llm;

public record LlmCompletion(String text, String provider, int promptTokens, int completionTokens) {}
