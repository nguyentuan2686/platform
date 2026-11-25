package dev.platform.payment.adapter.out.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.platform.payment.application.InsufficientCreditsException;
import dev.platform.payment.application.UpstreamUnavailableException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IdentityCreditsAdapterTest {

  final UUID account = UUID.randomUUID();
  final UUID order = UUID.randomUUID();
  final AtomicReference<String> body = new AtomicReference<>();
  final AtomicReference<String> token = new AtomicReference<>();
  HttpServer server;
  int status = 200;
  long delayMillis;

  IdentityCreditsAdapter start(Duration readTimeout) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/internal/credits/reversals",
        exchange -> {
          body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          token.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
          try {
            Thread.sleep(delayMillis);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] reply = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, reply.length);
          exchange.getResponseBody().write(reply);
          exchange.close();
        });
    server.start();
    return new IdentityCreditsAdapter(
        "http://127.0.0.1:" + server.getAddress().getPort(),
        "local-test-internal-token",
        Duration.ofSeconds(1),
        readTimeout);
  }

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void posts_the_reversal_with_the_internal_token() throws IOException {
    IdentityCreditsAdapter adapter = start(Duration.ofSeconds(3));

    adapter.reverse(account, order, 5);

    assertThat(token.get()).isEqualTo("local-test-internal-token");
    assertThat(body.get())
        .contains("\"account_id\":\"" + account + "\"")
        .contains("\"order_id\":\"" + order + "\"")
        .contains("\"credits\":5");
  }

  @Test
  void a_409_means_the_credits_are_used() throws IOException {
    status = 409;
    IdentityCreditsAdapter adapter = start(Duration.ofSeconds(3));

    assertThatThrownBy(() -> adapter.reverse(account, order, 5))
        .isInstanceOf(InsufficientCreditsException.class);
  }

  @Test
  void any_other_failure_is_upstream_unavailable() throws IOException {
    for (int s : new int[] {404, 500, 503}) {
      status = s;
      IdentityCreditsAdapter adapter = start(Duration.ofSeconds(3));
      assertThatThrownBy(() -> adapter.reverse(account, order, 5))
          .isInstanceOf(UpstreamUnavailableException.class);
      server.stop(0);
    }
  }

  @Test
  void a_slow_answer_times_out_as_upstream_unavailable() throws IOException {
    delayMillis = 1500;
    IdentityCreditsAdapter adapter = start(Duration.ofMillis(300));

    assertThatThrownBy(() -> adapter.reverse(account, order, 5))
        .isInstanceOf(UpstreamUnavailableException.class);
  }

  @Test
  void a_closed_port_is_upstream_unavailable() throws IOException {
    IdentityCreditsAdapter adapter = start(Duration.ofSeconds(1));
    server.stop(0);

    assertThatThrownBy(() -> adapter.reverse(account, order, 5))
        .isInstanceOf(UpstreamUnavailableException.class);
  }
}
