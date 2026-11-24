package dev.platform.reading.tuvi.adapter.config;

import dev.platform.reading.tuvi.adapter.out.chart.ChartSvcClient;
import dev.platform.reading.tuvi.application.ChartPort;
import dev.platform.reading.tuvi.application.KnowledgePort;
import dev.platform.reading.tuvi.application.ReadingCachePort;
import dev.platform.reading.tuvi.application.ReadingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the framework-free application service to its adapters. */
@Configuration(proxyBeanMethods = false)
class ReadingConfiguration {

  @Bean
  ChartPort chartPort(@Value("${chart-svc.url}") String url) {
    return new ChartSvcClient(url);
  }

  @Bean
  ReadingService readingService(ChartPort charts, KnowledgePort knowledge, ReadingCachePort cache) {
    return new ReadingService(charts, knowledge, cache);
  }
}
