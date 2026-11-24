package dev.platform.reading.tuvi;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

// allowEmptyShould remains on rules whose packages (application, adapter) are still empty.
@AnalyzeClasses(
    packages = "dev.platform.reading.tuvi",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule domainIsPure =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "jakarta..",
              "java.sql..",
              "javax.sql..",
              "tools.jackson..",
              "com.fasterxml..",
              "..adapter..",
              "..application..");

  @ArchTest
  static final ArchRule applicationDoesNotDependOnAdapters =
      noClasses()
          .that()
          .resideInAPackage("..application..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..adapter..")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule inboundAdaptersDoNotUseOutboundAdapters =
      noClasses()
          .that()
          .resideInAPackage("..adapter.in..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..adapter.out..")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule outboundAdaptersDoNotUseInboundAdapters =
      noClasses()
          .that()
          .resideInAPackage("..adapter.out..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..adapter.in..")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule noFieldInjection =
      noClasses()
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("org.springframework.beans.factory.annotation.Autowired")
          .allowEmptyShould(true);
}
