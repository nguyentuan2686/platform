package dev.platform.starter.messaging;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.CreateTopicsOptions;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Readiness <em>startup gate</em>: every declared {@link NewTopic} exists. A pod that starts before
 * the broker finished booting stays unready until its topics are there; missing topics are created
 * on the probe and reported {@code DOWN} until the next probe sees them.
 *
 * <p>Once all topics were observed the indicator latches {@code UP} for the life of the process and
 * never contacts the broker again: a broker outage after startup must not take HTTP paths that do
 * not need Kafka (login, order creation through the outbox, payment webhooks) out of their Service.
 *
 * <p>Cost: one lazily created, long-lived admin client; one deadline of {@code timeout} shared by
 * the list and create calls, so {@link #health()} never blocks longer than {@code timeout}; no
 * background tasks. Never throws.
 */
public class KafkaTopicsHealthIndicator implements HealthIndicator, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(KafkaTopicsHealthIndicator.class);

  private final ObjectProvider<NewTopic> topics;
  private final Duration timeout;
  private final Function<Map<String, Object>, Admin> adminFactory;
  private final Map<String, Object> adminProps;
  private final Object clientLock = new Object();

  private volatile boolean satisfied;
  private Admin client;

  public KafkaTopicsHealthIndicator(
      KafkaAdmin admin, ObjectProvider<NewTopic> topics, Duration timeout) {
    this(admin, topics, timeout, AdminClient::create);
  }

  KafkaTopicsHealthIndicator(
      KafkaAdmin admin,
      ObjectProvider<NewTopic> topics,
      Duration timeout,
      Function<Map<String, Object>, Admin> adminFactory) {
    this.topics = topics;
    this.timeout = timeout;
    this.adminFactory = adminFactory;
    Map<String, Object> props = new HashMap<>(admin.getConfigurationProperties());
    int ms = (int) timeout.toMillis();
    props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, ms);
    props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, ms);
    props.put(AdminClientConfig.SOCKET_CONNECTION_SETUP_TIMEOUT_MAX_MS_CONFIG, ms);
    this.adminProps = props;
  }

  @Override
  public Health health() {
    if (satisfied) {
      return Health.up().build();
    }
    long deadline = System.nanoTime() + timeout.toNanos();
    try {
      List<NewTopic> declared = topics.orderedStream().toList();
      if (declared.isEmpty()) {
        satisfied = true;
        return Health.up().build();
      }
      Admin admin = client();
      Set<String> existing =
          admin
              .listTopics(new ListTopicsOptions().timeoutMs(remainingMs(deadline)))
              .names()
              .get(remainingMs(deadline), TimeUnit.MILLISECONDS);
      List<NewTopic> missing = declared.stream().filter(t -> !existing.contains(t.name())).toList();
      if (missing.isEmpty()) {
        satisfied = true;
        return Health.up()
            .withDetail("topics", declared.stream().map(NewTopic::name).toList())
            .build();
      }
      create(admin, missing, deadline);
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

  /** One attempt per probe on the shared client, bounded by what is left of the deadline. */
  private void create(Admin admin, List<NewTopic> missing, long deadline)
      throws InterruptedException {
    try {
      admin
          .createTopics(missing, new CreateTopicsOptions().timeoutMs(remainingMs(deadline)))
          .all()
          .get(remainingMs(deadline), TimeUnit.MILLISECONDS);
    } catch (ExecutionException e) {
      if (!(e.getCause() instanceof TopicExistsException)) {
        LOG.warn("kafka topic creation failed: {}", message(e));
      }
    } catch (TimeoutException e) {
      LOG.warn("kafka topic creation timed out after {} ms", timeout.toMillis());
    }
  }

  private int remainingMs(long deadline) throws TimeoutException {
    long ms = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
    if (ms <= 0) {
      throw new TimeoutException("kafka topics check exceeded " + timeout.toMillis() + " ms");
    }
    return (int) ms;
  }

  private Admin client() {
    synchronized (clientLock) {
      if (client == null) {
        client = adminFactory.apply(adminProps);
      }
      return client;
    }
  }

  @Override
  public void close() {
    synchronized (clientLock) {
      if (client != null) {
        client.close(Duration.ofSeconds(1));
        client = null;
      }
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
