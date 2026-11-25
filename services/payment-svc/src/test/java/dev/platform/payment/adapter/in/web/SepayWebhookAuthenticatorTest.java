package dev.platform.payment.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SepayWebhookAuthenticatorTest {

  static final String SECRET = "local-test-sepay-secret";
  static final byte[] BODY = "{\"id\":1}".getBytes(StandardCharsets.UTF_8);

  static String hmac(String secret, byte[] body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(body));
  }

  @Test
  void apikey_mode_accepts_only_the_exact_apikey_header() throws Exception {
    SepayWebhookAuthenticator auth = new SepayWebhookAuthenticator("apikey", SECRET);

    assertThat(auth.authenticate("Apikey " + SECRET, null, BODY)).isTrue();
    assertThat(auth.authenticate("Apikey wrong-secret", null, BODY)).isFalse();
    assertThat(auth.authenticate("Apikey " + SECRET + "x", null, BODY)).isFalse();
    assertThat(auth.authenticate("Apikey ", null, BODY)).isFalse();
    assertThat(auth.authenticate(SECRET, null, BODY)).isFalse();
    assertThat(auth.authenticate("Bearer " + SECRET, null, BODY)).isFalse();
    assertThat(auth.authenticate(null, null, BODY)).isFalse();
    assertThat(auth.authenticate(null, hmac(SECRET, BODY), BODY)).isFalse();
  }

  @Test
  void hmac_mode_accepts_only_the_signature_of_the_exact_body() throws Exception {
    SepayWebhookAuthenticator auth = new SepayWebhookAuthenticator("hmac-sha256", SECRET);

    assertThat(auth.authenticate(null, hmac(SECRET, BODY), BODY)).isTrue();
    assertThat(auth.authenticate(null, hmac(SECRET, BODY).toUpperCase(), BODY)).isTrue();
    assertThat(auth.authenticate(null, hmac("other", BODY), BODY)).isFalse();
    assertThat(auth.authenticate(null, hmac(SECRET, "{\"id\":2}".getBytes()), BODY)).isFalse();
    assertThat(auth.authenticate(null, "abc", BODY)).isFalse();
    assertThat(auth.authenticate(null, "", BODY)).isFalse();
    assertThat(auth.authenticate(null, null, BODY)).isFalse();
    assertThat(auth.authenticate("Apikey " + SECRET, null, BODY)).isFalse();
  }

  @Test
  void an_unset_secret_rejects_everything() throws Exception {
    SepayWebhookAuthenticator apikey = new SepayWebhookAuthenticator("apikey", "");
    SepayWebhookAuthenticator hmac = new SepayWebhookAuthenticator("hmac-sha256", "");

    assertThat(apikey.authenticate("Apikey ", null, BODY)).isFalse();
    assertThat(hmac.authenticate(null, hmac("x", BODY), BODY)).isFalse();
  }

  @Test
  void an_unknown_mode_fails_at_startup_without_naming_the_secret() {
    assertThatThrownBy(() -> new SepayWebhookAuthenticator("basic", SECRET))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining(SECRET);
  }
}
