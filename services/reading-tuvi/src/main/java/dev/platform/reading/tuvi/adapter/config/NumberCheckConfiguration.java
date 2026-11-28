package dev.platform.reading.tuvi.adapter.config;

import dev.platform.reading.tuvi.adapter.in.schedule.NumberJobs;
import dev.platform.reading.tuvi.adapter.out.crypto.HmacNumberHasher;
import dev.platform.reading.tuvi.application.KnowledgePort;
import dev.platform.reading.tuvi.application.number.NumberCheckService;
import dev.platform.reading.tuvi.application.number.NumberCheckSettings;
import dev.platform.reading.tuvi.application.number.NumberHasherPort;
import dev.platform.reading.tuvi.application.number.NumberReading;
import dev.platform.reading.tuvi.application.number.NumberUsagePort;
import dev.platform.reading.tuvi.application.qa.IdentityPort;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the free number check (xem số): HMAC hasher, quota settings, use case and purge job. */
@Configuration(proxyBeanMethods = false)
class NumberCheckConfiguration {

  /** Fails startup when {@code NUMBER_CHECK_HMAC_KEY} is missing or too short. */
  @Bean
  NumberHasherPort numberHasher(@Value("${number-check.hmac-key:}") String key) {
    return new HmacNumberHasher(key);
  }

  @Bean
  NumberCheckSettings numberCheckSettings(
      @Value("${number-check.daily-per-subject:2}") int perSubject,
      @Value("${number-check.daily-per-ip:20}") int perIp) {
    return new NumberCheckSettings(perSubject, perIp);
  }

  @Bean
  NumberReading numberReading(KnowledgePort knowledge) {
    return new NumberReading(knowledge);
  }

  @Bean
  NumberCheckService numberCheckService(
      IdentityPort identity,
      NumberUsagePort usage,
      NumberHasherPort hasher,
      NumberReading reading,
      Clock clock,
      NumberCheckSettings settings) {
    return new NumberCheckService(identity, usage, hasher, reading, clock, settings);
  }

  @Bean
  @ConditionalOnProperty(
      name = "number-check.jobs-enabled",
      havingValue = "true",
      matchIfMissing = true)
  NumberJobs numberJobs(NumberCheckService service) {
    return new NumberJobs(service);
  }
}
