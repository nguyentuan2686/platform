package dev.platform.identity.domain;

import java.util.UUID;

/** Who a session belongs to: an anonymous guest or a logged-in account. */
public sealed interface Subject permits Subject.Guest, Subject.Account {

  UUID id();

  record Guest(UUID id) implements Subject {}

  record Account(UUID id) implements Subject {}
}
