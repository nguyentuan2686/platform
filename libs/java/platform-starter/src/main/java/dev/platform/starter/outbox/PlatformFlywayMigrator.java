package dev.platform.starter.outbox;

import java.util.Arrays;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;

/**
 * Applies the starter's own migrations ({@value #LOCATION}) with a separate history table ({@value
 * #HISTORY_TABLE}), so platform versions never interleave with a service's versions.
 *
 * <p>Runs once, at bean initialization, before every {@link FlywayMigrationInitializer} (see {@link
 * #runBeforeServiceMigrations()}). It baselines at version 0 when the schema already holds objects
 * without a platform history (e.g. a service that adopts the outbox later), so {@code V1} and later
 * still apply. Deliberately not a {@link Flyway} bean: that would make Boot's Flyway
 * auto-configuration back off for the service's own migrations.
 */
public class PlatformFlywayMigrator implements InitializingBean {

  static final String BEAN_NAME = "platformFlywayMigrator";
  static final String LOCATION = "classpath:db/platform";
  static final String HISTORY_TABLE = "platform_schema_history";

  private static final Logger log = LoggerFactory.getLogger(PlatformFlywayMigrator.class);

  private final DataSource dataSource;

  public PlatformFlywayMigrator(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public void afterPropertiesSet() {
    MigrateResult result =
        Flyway.configure(PlatformFlywayMigrator.class.getClassLoader())
            .dataSource(dataSource)
            .locations(LOCATION)
            .table(HISTORY_TABLE)
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate();
    log.info(
        "Platform schema at version {} ({} migration(s) applied)",
        result.targetSchemaVersion != null
            ? result.targetSchemaVersion
            : result.initialSchemaVersion,
        result.migrationsExecuted);
  }

  /**
   * Makes every service {@link FlywayMigrationInitializer} depend on this migrator, so platform
   * tables exist before service migrations run (they may reference them).
   */
  static BeanFactoryPostProcessor runBeforeServiceMigrations() {
    return beanFactory -> {
      for (String name :
          beanFactory.getBeanNamesForType(FlywayMigrationInitializer.class, true, false)) {
        BeanDefinition definition = beanFactory.getBeanDefinition(name);
        String[] dependsOn = definition.getDependsOn();
        if (dependsOn == null || !Arrays.asList(dependsOn).contains(BEAN_NAME)) {
          String[] updated =
              dependsOn == null ? new String[1] : Arrays.copyOf(dependsOn, dependsOn.length + 1);
          updated[updated.length - 1] = BEAN_NAME;
          definition.setDependsOn(updated);
        }
      }
    };
  }
}
