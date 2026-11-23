package dev.platform.starter;

import java.io.IOException;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.util.ClassUtils;

/**
 * Loads {@code platform-defaults.properties} as the lowest-precedence property source, so any
 * service can override every platform default through its own configuration or environment.
 */
public class PlatformDefaultsPostProcessor implements EnvironmentPostProcessor, Ordered {

  static final String RESOURCE = "platform-defaults.properties";
  static final String SOURCE_NAME = "platformDefaults";
  static final String DB_SOURCE_NAME = "platformDefaultsDb";
  static final String READY_GROUP = "management.endpoint.health.group.ready.include";
  static final String JDBC_HEALTH =
      "org.springframework.boot.jdbc.health.DataSourceHealthIndicator";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication app) {
    try {
      var props = PropertiesLoaderUtils.loadProperties(new ClassPathResource(RESOURCE));
      environment.getPropertySources().addLast(new PropertiesPropertySource(SOURCE_NAME, props));
      if (ClassUtils.isPresent(JDBC_HEALTH, getClass().getClassLoader())) {
        // A DataSource health indicator exists, so readiness also depends on the database.
        environment
            .getPropertySources()
            .addBefore(
                SOURCE_NAME,
                new MapPropertySource(DB_SOURCE_NAME, Map.of(READY_GROUP, "readinessState,db")));
      }
    } catch (IOException e) {
      throw new IllegalStateException("Cannot load " + RESOURCE, e);
    }
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
