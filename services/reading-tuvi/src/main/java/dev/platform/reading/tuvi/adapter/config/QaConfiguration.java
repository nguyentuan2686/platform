package dev.platform.reading.tuvi.adapter.config;

import dev.platform.reading.tuvi.adapter.in.schedule.QaJobs;
import dev.platform.reading.tuvi.adapter.out.crypto.AesGcmQaCipherAdapter;
import dev.platform.reading.tuvi.adapter.out.identity.IdentitySvcClient;
import dev.platform.reading.tuvi.application.ChartPort;
import dev.platform.reading.tuvi.application.KnowledgePort;
import dev.platform.reading.tuvi.application.llm.LlmGateway;
import dev.platform.reading.tuvi.application.qa.IdentityPort;
import dev.platform.reading.tuvi.application.qa.QaAnswerCachePort;
import dev.platform.reading.tuvi.application.qa.QaFrames;
import dev.platform.reading.tuvi.application.qa.QaRepositoryPort;
import dev.platform.reading.tuvi.application.qa.QaRetentionUseCase;
import dev.platform.reading.tuvi.application.qa.QaRetryUseCase;
import dev.platform.reading.tuvi.application.qa.QaService;
import dev.platform.reading.tuvi.application.qa.QaSettings;
import dev.platform.reading.tuvi.application.qa.QaStore;
import dev.platform.reading.tuvi.application.qa.QaTextCipherPort;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Wires the Q&A use case, identity-svc client, cipher and the bounded Q&A worker pool. */
@Configuration(proxyBeanMethods = false)
class QaConfiguration {

  static final int QUEUE_CAPACITY = 20;

  @Bean
  IdentityPort identityPort(
      @Value("${identity-svc.url}") String url,
      @Value("${platform.internal-token:}") String internalToken) {
    return new IdentitySvcClient(url, internalToken);
  }

  /** Fails startup when {@code QA_ENC_KEY} is missing or not base64 of 32 bytes. */
  @Bean
  QaTextCipherPort qaTextCipher(@Value("${qa.enc-key:}") String key) {
    return new AesGcmQaCipherAdapter(key, new SecureRandom());
  }

  @Bean
  QaSettings qaSettings(
      @Value("${qa.free-daily-per-subject:3}") int perSubject,
      @Value("${qa.free-global-daily-cap:500}") int globalCap,
      @Value("${qa.retry-window:PT24H}") Duration retryWindow,
      @Value("${qa.consume-refund-grace:PT2M}") Duration consumeRefundGrace) {
    return new QaSettings(perSubject, globalCap, retryWindow, consumeRefundGrace);
  }

  @Bean
  QaFrames qaFrames(KnowledgePort knowledge) {
    return new QaFrames(knowledge);
  }

  @Bean
  QaStore qaStore(QaRepositoryPort repo) {
    return new QaStore(repo);
  }

  @Bean
  QaService qaService(
      IdentityPort identity,
      ChartPort charts,
      KnowledgePort knowledge,
      LlmGateway gateway,
      QaRepositoryPort repo,
      QaStore store,
      QaAnswerCachePort cache,
      QaTextCipherPort cipher,
      QaFrames frames,
      Clock clock,
      QaSettings settings) {
    return new QaService(
        identity, charts, knowledge, gateway, repo, store, cache, cipher, frames, clock, settings);
  }

  @Bean
  QaRetryUseCase qaRetryUseCase(
      QaRepositoryPort repo,
      QaService qa,
      IdentityPort identity,
      QaSettings settings,
      Clock clock) {
    return new QaRetryUseCase(repo, qa, identity, settings, clock);
  }

  @Bean
  QaRetentionUseCase qaRetentionUseCase(
      QaRepositoryPort repo, @Value("${qa.free-retention:P90D}") Duration retention) {
    return new QaRetentionUseCase(repo, retention);
  }

  /** Retry/refund/recovery every {@code QA_JOB_INTERVAL}, retention every 6 h. */
  @Bean
  @ConditionalOnProperty(name = "qa.jobs-enabled", havingValue = "true", matchIfMissing = true)
  QaJobs qaJobs(
      QaRetryUseCase retry,
      QaRetentionUseCase retention,
      Clock clock,
      @Value("${qa.job-interval:PT1M}") Duration interval) {
    return new QaJobs(retry, retention, clock, interval);
  }

  /** {@code QA_WORKERS} threads, queue of 20; a full queue rejects (handled as a failure). */
  @Bean("qaExecutor")
  ThreadPoolTaskExecutor qaExecutor(@Value("${qa.workers:2}") int workers) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(workers);
    executor.setMaxPoolSize(workers);
    executor.setQueueCapacity(QUEUE_CAPACITY);
    executor.setThreadNamePrefix("qa-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    return executor;
  }
}
