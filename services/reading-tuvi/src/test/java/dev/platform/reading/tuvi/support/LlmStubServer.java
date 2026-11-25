package dev.platform.reading.tuvi.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Local HTTP stub standing in for an LLM provider; records requests, replies as configured. */
public final class LlmStubServer implements AutoCloseable {

  /** A recorded request. */
  public record Recorded(String method, String path, Map<String, String> headers, String body) {}

  private final HttpServer server;
  private final List<Recorded> requests = new CopyOnWriteArrayList<>();

  public volatile int status = 200;
  public volatile String body = "{}";
  public volatile Map<String, String> responseHeaders = Map.of();
  public volatile long delayMillis = 0;

  public LlmStubServer() {
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    server.createContext(
        "/",
        exchange -> {
          var headers = new java.util.HashMap<String, String>();
          exchange
              .getRequestHeaders()
              .forEach((k, v) -> headers.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0)));
          String reqBody =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          requests.add(
              new Recorded(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().getPath(),
                  headers,
                  reqBody));
          if (delayMillis > 0) {
            try {
              Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          byte[] out = body.getBytes(StandardCharsets.UTF_8);
          responseHeaders.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          try {
            exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
              exchange.getResponseBody().write(out);
            }
          } catch (IOException ignored) {
            // client gave up (timeout test)
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  public URI baseUrl() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  public List<Recorded> requests() {
    return requests;
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
