package dev.platform.starter.web;

import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
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

  /**
   * Internal-token guard, active only when {@code platform.internal-token} (env {@code
   * INTERNAL_API_TOKEN}) is non-empty. {@code platform.internal-token.open-paths} (Ant patterns,
   * default {@code /health/**}) stay open.
   */
  @Bean
  @ConditionalOnExpression("!'${platform.internal-token:}'.isEmpty()")
  InternalTokenFilter internalTokenFilter(
      @Value("${platform.internal-token}") String token, Environment environment) {
    List<String> openPaths =
        Binder.get(environment)
            .bind("platform.internal-token.open-paths", Bindable.listOf(String.class))
            .orElse(List.of("/health/**"));
    return new InternalTokenFilter(token, openPaths);
  }

  @Bean
  @ConditionalOnExpression("'${platform.internal-token:}'.isEmpty()")
  ApplicationRunner internalTokenDisabledWarning() {
    return args ->
        LoggerFactory.getLogger(PlatformWebAutoConfiguration.class)
            .warn("platform.internal-token is not set: internal-token filter disabled");
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
