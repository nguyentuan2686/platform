package dev.platform.__JAVA_PKG__.adapter.config;

import dev.platform.__JAVA_PKG__.application.InfoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the framework-free application service to its adapters. */
@Configuration(proxyBeanMethods = false)
public class __CLASS__Configuration {

  @Bean
  InfoService infoService(@Value("${spring.application.name}") String serviceName) {
    return new InfoService(serviceName);
  }
}
