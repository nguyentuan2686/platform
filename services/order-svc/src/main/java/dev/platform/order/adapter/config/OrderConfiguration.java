package dev.platform.order.adapter.config;

import dev.platform.order.application.OrderEventsPort;
import dev.platform.order.application.OrderRepositoryPort;
import dev.platform.order.application.OrderService;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the framework-free application service to its adapters. */
@Configuration(proxyBeanMethods = false)
public class OrderConfiguration {

  /** Microsecond ticks: what the database stores, so POST and GET report the same createdAt. */
  @Bean
  Clock clock() {
    return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
  }

  @Bean
  OrderService orderService(OrderRepositoryPort repository, OrderEventsPort events, Clock clock) {
    return new OrderService(repository, events, clock);
  }
}
