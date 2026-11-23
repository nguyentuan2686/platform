package dev.platform.starter;

import java.io.IOException;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * Loads {@code platform-defaults.properties} as the lowest-precedence property source, so any
 * service can override every platform default through its own configuration or environment.
 */
public class PlatformDefaultsPostProcessor implements EnvironmentPostProcessor, Ordered {

  static final String RESOURCE = "platform-defaults.properties";
  static final String SOURCE_NAME = "platformDefaults";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication app) {
    try {
      var props = PropertiesLoaderUtils.loadProperties(new ClassPathResource(RESOURCE));
      environment.getPropertySources().addLast(new PropertiesPropertySource(SOURCE_NAME, props));
    } catch (IOException e) {
      throw new IllegalStateException("Cannot load " + RESOURCE, e);
    }
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
