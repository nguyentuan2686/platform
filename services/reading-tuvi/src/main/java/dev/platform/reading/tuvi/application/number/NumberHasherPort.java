package dev.platform.reading.tuvi.application.number;

/** Keyed one-way hashes of the checked number and of the client IP. */
public interface NumberHasherPort {

  /** HMAC of {@code <kind>|<normalized number>}. */
  byte[] checkHash(String material);

  /** HMAC of the IP; an IPv6 address is reduced to its /64 first. */
  byte[] ipHash(String ip);
}
