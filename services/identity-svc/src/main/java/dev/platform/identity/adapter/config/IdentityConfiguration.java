package dev.platform.identity.adapter.config;

import dev.platform.identity.adapter.out.mail.SmtpMailerAdapter;
import dev.platform.identity.adapter.out.persistence.JdbcAccountRepository;
import dev.platform.identity.adapter.out.persistence.JdbcMagicLinkRepository;
import dev.platform.identity.adapter.out.persistence.JdbcSessionRepository;
import dev.platform.identity.application.AccountMergePort;
import dev.platform.identity.application.AccountRepositoryPort;
import dev.platform.identity.application.EntitlementGrantPort;
import dev.platform.identity.application.GuestUseCase;
import dev.platform.identity.application.MagicLinkRepositoryPort;
import dev.platform.identity.application.MagicLinkUseCase;
import dev.platform.identity.application.MailerPort;
import dev.platform.identity.application.SessionRepositoryPort;
import dev.platform.identity.application.SessionUseCase;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSender;

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

  /** Replaced by the real merge in a later task. */
  @Bean
  AccountMergePort accountMerge() {
    return (guestId, accountId) -> {};
  }

  /** Replaced by the real grant in a later task. */
  @Bean
  EntitlementGrantPort entitlementGrant() {
    return accountId -> {};
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
