package dev.platform.reading.tuvi.application.llm;

import java.util.List;

public record LlmRequest(
    List<LlmMessage> messages, int maxTokens, double temperature, LlmTier tier) {}
