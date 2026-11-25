package dev.platform.identity.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.platform.identity.domain.Topic;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ConsumeRequest(
    @JsonProperty("account_id") @NotNull UUID accountId,
    @JsonProperty("reading_id") @NotNull UUID readingId,
    @NotNull Topic topic) {}
