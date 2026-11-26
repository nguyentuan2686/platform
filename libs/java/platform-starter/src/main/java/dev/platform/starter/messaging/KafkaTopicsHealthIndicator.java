package dev.platform.starter.messaging;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Readiness: every declared {@link NewTopic} exists. A missing topic is (re)created through {@link
 * KafkaAdmin#createOrModifyTopics} and reported {@code DOWN} until the next probe sees it, so a pod
 * that started before the broker finished booting becomes ready on its own. Never throws and never
 * blocks longer than the configured timeout.
 */
public class KafkaTopicsHealthIndicator implements HealthIndicator {

  private static final Logger LOG = LoggerFactory.getLogger(KafkaTopicsHealthIndicator.class);

  private final KafkaAdmin admin;
  private final ObjectProvider<NewTopic> topics;
  private final Duration timeout;

  public KafkaTopicsHealthIndicator(
      KafkaAdmin admin, ObjectProvider<NewTopic> topics, Duration timeout) {
    this.admin = admin;
    this.topics = topics;
    this.timeout = timeout;
  }

  @Override
  public Health health() {
    try {
      List<NewTopic> declared = topics.orderedStream().toList();
      if (declared.isEmpty()) {
        return Health.up().build();
      }
      List<String> names = declared.stream().map(NewTopic::name).toList();
      Set<String> existing = listTopics();
      List<NewTopic> missing = declared.stream().filter(t -> !existing.contains(t.name())).toList();
      if (missing.isEmpty()) {
        return Health.up().withDetail("topics", names).build();
      }
      create(missing);
      return Health.down()
          .withDetail("missing", missing.stream().map(NewTopic::name).toList())
          .build();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return down(e);
    } catch (ExecutionException | TimeoutException | RuntimeException e) {
      return down(e);
    }
  }

  private Set<String> listTopics()
      throws InterruptedException, ExecutionException, TimeoutException {
    Map<String, Object> props = new HashMap<>(admin.getConfigurationProperties());
    int ms = (int) timeout.toMillis();
    props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, ms);
    props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, ms);
    props.put(AdminClientConfig.SOCKET_CONNECTION_SETUP_TIMEOUT_MAX_MS_CONFIG, ms);
    try (AdminClient client = AdminClient.create(props)) {
      return client
          .listTopics(new ListTopicsOptions().timeoutMs(ms))
          .names()
          .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  /** One attempt per probe, bounded by the timeout; the next probe re-checks. */
  private void create(List<NewTopic> missing) throws InterruptedException {
    CompletableFuture<Void> attempt =
        CompletableFuture.runAsync(
            () -> admin.createOrModifyTopics(missing.toArray(NewTopic[]::new)));
    try {
      attempt.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (ExecutionException | TimeoutException e) {
      LOG.warn("kafka topic creation failed or timed out: {}", message(e));
    }
  }

  private Health down(Exception e) {
    String message = message(e);
    LOG.warn("kafka topics health check failed: {}", message);
    return Health.down().withDetail("error", message).build();
  }

  private static String message(Exception e) {
    Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
    return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
  }
}
