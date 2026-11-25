package dev.platform.identity.application;

import java.util.UUID;

public interface ProcessedEventPort {

  /** Records the event; false if it was already processed. */
  boolean markProcessed(UUID eventId);
}
