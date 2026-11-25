package dev.platform.reading.tuvi.application.qa;

/**
 * Authenticated encryption of question and answer text. {@code aad} binds a ciphertext to its row:
 * {@code <reading_id>:<seq>} for messages, the cache key for cached answers.
 */
public interface QaTextCipherPort {

  byte[] encrypt(String aad, String text);

  /** Fails (without echoing anything) when the data was tampered with or the AAD differs. */
  String decrypt(String aad, byte[] data);
}
