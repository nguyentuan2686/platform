package dev.platform.identity.application;

import dev.platform.identity.domain.ChartProfile;

/** A profile with whether its birth data is locked for the owner. */
public record ProfileView(ChartProfile profile, boolean locked) {}
