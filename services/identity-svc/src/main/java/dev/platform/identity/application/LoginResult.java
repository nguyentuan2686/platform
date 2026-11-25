package dev.platform.identity.application;

public record LoginResult(IssuedSession session, boolean created) {}
