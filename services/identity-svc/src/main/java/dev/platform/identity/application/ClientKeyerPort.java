package dev.platform.identity.application;

import dev.platform.identity.domain.ClientKey;

/** Keys a normalized client address ({@code ClientAddress.normalize}) with a server secret. */
public interface ClientKeyerPort {

  ClientKey key(String normalizedAddress);
}
