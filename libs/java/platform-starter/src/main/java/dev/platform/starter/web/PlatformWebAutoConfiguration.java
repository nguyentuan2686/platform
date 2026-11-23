package dev.platform.starter.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Registers the platform's Problem Details handler for servlet web applications only. Health probe
 * and other defaults live in {@code platform-defaults.properties}.
 */
@AutoConfiguration(
    beforeName = "org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(ResponseEntityExceptionHandler.class)
public class PlatformWebAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(PlatformProblemHandler.class)
  PlatformProblemHandler platformProblemHandler() {
    return new PlatformProblemHandler();
  }

  /** Only active when Bean Validation is on the classpath. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "jakarta.validation.ConstraintViolationException")
  static class ConstraintViolationConfiguration {

    @Bean
    @ConditionalOnMissingBean(PlatformConstraintViolationHandler.class)
    PlatformConstraintViolationHandler platformConstraintViolationHandler() {
      return new PlatformConstraintViolationHandler();
    }
  }
}
