package dev.platform.identity.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RefundRequest(@JsonProperty("reading_id") @NotNull UUID readingId) {}
