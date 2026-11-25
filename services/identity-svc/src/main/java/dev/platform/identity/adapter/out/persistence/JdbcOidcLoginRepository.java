package dev.platform.identity.adapter.out.persistence;

import dev.platform.identity.application.OidcLoginRepositoryPort;
import dev.platform.identity.application.PendingOidcLogin;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcOidcLoginRepository implements OidcLoginRepositoryPort {

  private final JdbcClient jdbc;

  public JdbcOidcLoginRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void save(PendingOidcLogin login) {
    jdbc.sql(
            "insert into oidc_login (state, nonce, code_verifier, redirect_uri, guest_id,"
                + " expires_at) values (:state, :nonce, :verifier, :redirect, :guest, :expires)")
        .param("state", login.state())
        .param("nonce", login.nonce())
        .param("verifier", login.codeVerifier())
        .param("redirect", login.redirectUri().toString())
        .param("guest", login.guestId(), java.sql.Types.OTHER)
        .param("expires", Timestamps.toDb(login.expiresAt()))
        .update();
  }

  @Override
  public Optional<PendingOidcLogin> consume(String state, Instant now) {
    return jdbc.sql(
            "update oidc_login set used_at = :now"
                + " where state = :state and used_at is null and expires_at > :now"
                + " returning nonce, code_verifier, redirect_uri, guest_id, expires_at")
        .param("state", state)
        .param("now", Timestamps.toDb(now))
        .query(
            (rs, i) ->
                new PendingOidcLogin(
                    state,
                    rs.getString("nonce"),
                    rs.getString("code_verifier"),
                    URI.create(rs.getString("redirect_uri")),
                    rs.getObject("guest_id", UUID.class),
                    Timestamps.read(rs, "expires_at")))
        .optional();
  }
}
