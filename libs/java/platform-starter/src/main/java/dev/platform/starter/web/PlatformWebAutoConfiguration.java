package dev.platform.starter.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Registers the platform's Problem Details handler for servlet web applications only. Health probe
 * and other defaults live in {@code platform-defaults.properties}.
 */
@AutoConfiguration(
    beforeName = "org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PlatformWebAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(PlatformProblemHandler.class)
  PlatformProblemHandler platformProblemHandler() {
    return new PlatformProblemHandler();
  }
}
