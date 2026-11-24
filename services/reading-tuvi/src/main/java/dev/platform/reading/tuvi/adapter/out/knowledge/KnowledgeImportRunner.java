package dev.platform.reading.tuvi.adapter.out.knowledge;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Imports {@code KNOWLEDGE_DIR} at startup. Boot publishes ReadinessState.ACCEPTING_TRAFFIC only
 * after all runners return, so /health/ready stays DOWN until the import is done (and the app fails
 * to start if a file is invalid).
 */
@Component
public class KnowledgeImportRunner implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(KnowledgeImportRunner.class);

  private final JsonlKnowledgeImporter importer;
  private final String dir;

  public KnowledgeImportRunner(
      JsonlKnowledgeImporter importer, @Value("${knowledge.dir:}") String dir) {
    this.importer = importer;
    this.dir = dir;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (dir.isBlank()) {
      LOG.info("KNOWLEDGE_DIR not set; skipping knowledge import");
      return;
    }
    ImportResult r = importer.importDir(Path.of(dir));
    LOG.info(
        "knowledge import done: inserted={} updated={} unchanged={}",
        r.inserted(),
        r.updated(),
        r.unchanged());
  }
}
