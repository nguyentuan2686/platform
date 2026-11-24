package dev.platform.reading.tuvi;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import dev.platform.reading.tuvi.archfixture.domain.JacksonLeakingDomain;
import org.junit.jupiter.api.Test;

/** Proves the architecture rules are not vacuous by checking them against a known violator. */
class ArchitectureRulesTest {

  @Test
  void domain_purity_rule_rejects_jackson_3() {
    var classes = new ClassFileImporter().importClasses(JacksonLeakingDomain.class);

    assertThatThrownBy(() -> ArchitectureTest.domainIsPure.check(classes))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("tools.jackson");
  }
}
