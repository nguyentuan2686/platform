package dev.platform.starter.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.testcontainers.redpanda.RedpandaContainer;

class KafkaTopicsHealthIndicatorIT {

  static final Duration DEADLINE = Duration.ofSeconds(30);

  static final RedpandaContainer REDPANDA =
      new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v26.1.1")
          .withCommand("redpanda", "start", "--mode=dev-container", "--smp=1", "--memory=512M");

  static {
    REDPANDA.start();
  }

  private static KafkaTopicsHealthIndicator indicator(
      String bootstrap, Duration timeout, NewTopic... topics) {
    var beans = new StaticListableBeanFactory();
    for (int i = 0; i < topics.length; i++) {
      beans.addBean("topic" + i, topics[i]);
    }
    var admin = new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap));
    return new KafkaTopicsHealthIndicator(admin, beans.getBeanProvider(NewTopic.class), timeout);
  }

  @Test
  void missing_topic_is_created_and_reported_down_then_up() throws Exception {
    var topic = TopicBuilder.name("t.health.v1").partitions(1).replicas(1).build();
    var indicator = indicator(REDPANDA.getBootstrapServers(), Duration.ofSeconds(10), topic);

    Health first = indicator.health();

    assertThat(first.getStatus()).isEqualTo(Status.DOWN);
    assertThat(first.getDetails().get("missing")).isEqualTo(List.of("t.health.v1"));
    Awaitility.await()
        .atMost(DEADLINE)
        .pollInterval(Duration.ofMillis(500))
        .until(() -> indicator.health().getStatus().equals(Status.UP));
    try (var client =
        AdminClient.create(
            Map.<String, Object>of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers()))) {
      Set<String> names = client.listTopics().names().get(10, TimeUnit.SECONDS);
      assertThat(names).contains("t.health.v1");
    }
  }

  @Test
  void broker_unreachable_is_down_within_timeout() {
    var topic = TopicBuilder.name("t.unreachable.v1").partitions(1).replicas(1).build();
    var indicator = indicator("localhost:1", Duration.ofSeconds(1), topic);

    Health health = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> indicator.health());

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsKey("error");
  }

  @Test
  void no_declared_topics_is_up() {
    var indicator = indicator("localhost:1", Duration.ofSeconds(1));

    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
  }
}
