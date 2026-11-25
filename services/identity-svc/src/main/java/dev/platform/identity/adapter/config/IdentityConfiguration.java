package dev.platform.identity.adapter.config;

import dev.platform.identity.adapter.out.crypto.AesGcmBirthCipherAdapter;
import dev.platform.identity.adapter.out.mail.SmtpMailerAdapter;
import dev.platform.identity.adapter.out.oidc.DisabledOidcAdapter;
import dev.platform.identity.adapter.out.oidc.NimbusOidcAdapter;
import dev.platform.identity.adapter.out.persistence.JdbcAccountRepository;
import dev.platform.identity.adapter.out.persistence.JdbcLedgerRepository;
import dev.platform.identity.adapter.out.persistence.JdbcMagicLinkRepository;
import dev.platform.identity.adapter.out.persistence.JdbcOidcLoginRepository;
import dev.platform.identity.adapter.out.persistence.JdbcProfileRepository;
import dev.platform.identity.adapter.out.persistence.JdbcSessionRepository;
import dev.platform.identity.adapter.out.persistence.JdbcShareRepository;
import dev.platform.identity.application.AccountMergePort;
import dev.platform.identity.application.AccountNotFoundException;
import dev.platform.identity.application.AccountRepositoryPort;
import dev.platform.identity.application.BirthCipherPort;
import dev.platform.identity.application.EntitlementGrantPort;
import dev.platform.identity.application.EntitlementUseCase;
import dev.platform.identity.application.GuestUseCase;
import dev.platform.identity.application.LedgerPort;
import dev.platform.identity.application.MagicLinkRepositoryPort;
import dev.platform.identity.application.MagicLinkUseCase;
import dev.platform.identity.application.MailerPort;
import dev.platform.identity.application.OidcLoginRepositoryPort;
import dev.platform.identity.application.OidcLoginUseCase;
import dev.platform.identity.application.OidcPort;
import dev.platform.identity.application.PaymentGrantUseCase;
import dev.platform.identity.application.ProcessedEventPort;
import dev.platform.identity.application.ProfileRepositoryPort;
import dev.platform.identity.application.ProfileUseCase;
import dev.platform.identity.application.SessionRepositoryPort;
import dev.platform.identity.application.SessionUseCase;
import dev.platform.identity.application.ShareRepositoryPort;
import dev.platform.identity.application.ShareUseCase;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.backoff.ExponentialBackOff;

/** Wires the framework-free application services to their adapters. */
@Configuration(proxyBeanMethods = false)
class IdentityConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  SecureRandom secureRandom() {
    return new SecureRandom();
  }

  @Bean
  AccountRepositoryPort accountRepository(JdbcClient jdbc) {
    return new JdbcAccountRepository(jdbc);
  }

  @Bean
  SessionRepositoryPort sessionRepository(JdbcClient jdbc) {
    return new JdbcSessionRepository(jdbc);
  }

  @Bean
  MagicLinkRepositoryPort magicLinkRepository(JdbcClient jdbc) {
    return new JdbcMagicLinkRepository(jdbc);
  }

  @Bean
  MailerPort mailer(JavaMailSender sender, @Value("${identity.mail.from}") String from) {
    return new SmtpMailerAdapter(sender, from);
  }

  /** Fails startup when PROFILE_ENC_KEY is missing or is not base64 of 32 bytes. */
  @Bean
  BirthCipherPort birthCipher(
      @Value("${identity.profile.enc-key:}") String key, SecureRandom random) {
    return new AesGcmBirthCipherAdapter(key, random);
  }

  @Bean
  ProfileRepositoryPort profileRepository(JdbcClient jdbc) {
    return new JdbcProfileRepository(jdbc);
  }

  /** Also the {@code AccountMergePort}: the guest-to-account merge is the profile move. */
  @Bean
  ProfileUseCase profileUseCase(
      ProfileRepositoryPort profiles,
      SessionRepositoryPort sessions,
      BirthCipherPort cipher,
      Clock clock,
      TransactionOperations tx) {
    return new ProfileUseCase(profiles, sessions, cipher, clock, tx);
  }

  @Bean
  ShareRepositoryPort shareRepository(JdbcClient jdbc) {
    return new JdbcShareRepository(jdbc);
  }

  @Bean
  ShareUseCase shareUseCase(
      ShareRepositoryPort shares,
      BirthCipherPort cipher,
      Clock clock,
      SecureRandom random,
      TransactionOperations tx) {
    return new ShareUseCase(shares, cipher, clock, random, tx);
  }

  @Bean
  JdbcLedgerRepository ledgerRepository(JdbcClient jdbc) {
    return new JdbcLedgerRepository(jdbc);
  }

  /** Also the {@code EntitlementGrantPort}: the one-time signup bonus is a ledger entry. */
  @Bean
  EntitlementUseCase entitlementUseCase(
      LedgerPort ledger, ProfileRepositoryPort profiles, Clock clock, TransactionOperations tx) {
    return new EntitlementUseCase(ledger, profiles, clock, tx);
  }

  @Bean
  PaymentGrantUseCase paymentGrantUseCase(
      LedgerPort ledger, ProcessedEventPort processed, Clock clock, TransactionOperations tx) {
    return new PaymentGrantUseCase(ledger, processed, clock, tx);
  }

  /**
   * Contract errors (unknown product or account, bad envelope) go straight to {@code <topic>.DLT}.
   * Anything else (database down, lock timeout) is retried with exponential backoff and no retry
   * limit, so an outage pauses consumption instead of discarding paid grants.
   */
  @Bean
  CommonErrorHandler kafkaErrorHandler(
      KafkaTemplate<?, ?> template,
      @Value("${identity.kafka.retry.initial:1s}") Duration initial,
      @Value("${identity.kafka.retry.max:60s}") Duration max) {
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            template, (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1));
    ExponentialBackOff backOff = new ExponentialBackOff(initial.toMillis(), 2.0);
    backOff.setMaxInterval(max.toMillis());
    DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
    handler.addNotRetryableExceptions(
        IllegalArgumentException.class, AccountNotFoundException.class);
    return handler;
  }

  @Bean
  OidcLoginRepositoryPort oidcLoginRepository(JdbcClient jdbc) {
    return new JdbcOidcLoginRepository(jdbc);
  }

  /** Google when a client id is configured; otherwise a port that answers "not configured". */
  @Bean
  OidcPort oidcPort(
      @Value("${identity.oidc.google.issuer}") String issuer,
      @Value("${identity.oidc.google.client-id}") String clientId,
      @Value("${identity.oidc.google.client-secret}") String clientSecret,
      Clock clock) {
    if (clientId.isBlank()) {
      return new DisabledOidcAdapter();
    }
    return new NimbusOidcAdapter(URI.create(issuer), clientId, clientSecret, clock);
  }

  @Bean
  OidcLoginUseCase oidcLoginUseCase(
      AccountRepositoryPort accounts,
      SessionRepositoryPort sessions,
      OidcLoginRepositoryPort logins,
      OidcPort oidc,
      AccountMergePort merges,
      EntitlementGrantPort grants,
      Clock clock,
      SecureRandom random,
      TransactionOperations tx,
      @Value("${identity.oidc.google.redirect-uris}") String redirectUris) {
    Set<URI> allowed =
        Arrays.stream(redirectUris.split(","))
            .map(String::strip)
            .filter(s -> !s.isEmpty())
            .map(URI::create)
            .collect(Collectors.toUnmodifiableSet());
    return new OidcLoginUseCase(
        accounts, sessions, logins, oidc, merges, grants, clock, random, allowed, tx);
  }

  @Bean
  GuestUseCase guestUseCase(SessionRepositoryPort sessions, Clock clock, SecureRandom random) {
    return new GuestUseCase(sessions, clock, random);
  }

  @Bean
  SessionUseCase sessionUseCase(
      SessionRepositoryPort sessions, AccountRepositoryPort accounts, Clock clock) {
    return new SessionUseCase(sessions, accounts, clock);
  }

  @Bean
  MagicLinkUseCase magicLinkUseCase(
      AccountRepositoryPort accounts,
      SessionRepositoryPort sessions,
      MagicLinkRepositoryPort links,
      MailerPort mailer,
      AccountMergePort merges,
      EntitlementGrantPort grants,
      Clock clock,
      SecureRandom random,
      @Value("${identity.web-base-url}") String webBaseUrl) {
    return new MagicLinkUseCase(
        accounts, sessions, links, mailer, merges, grants, clock, random, URI.create(webBaseUrl));
  }
}
