package dev.platform.identity.domain;

/**
 * Who asked for a magic link, for the per-client cap: a keyed hash (64 hex) of the client's {@link
 * ClientAddress}, made by the {@code ClientKeyerPort}. Only this is stored, never the address;
 * {@code toString} redacts it.
 */
public record ClientKey(String value) {

  public ClientKey {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("not a client key");
    }
  }

  @Override
  public String toString() {
    return "ClientKey[redacted]";
  }
}
