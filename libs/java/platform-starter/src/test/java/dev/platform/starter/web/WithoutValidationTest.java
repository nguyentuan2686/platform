package dev.platform.starter.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class WithoutValidationTest {

  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(PlatformWebAutoConfiguration.class));

  @Test
  void context_starts_and_problem_handler_works_without_jakarta_validation() {
    runner
        .withClassLoader(new FilteredClassLoader(ConstraintViolationException.class))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(PlatformProblemHandler.class);
              assertThat(context).doesNotHaveBean(PlatformConstraintViolationHandler.class);
              ResponseEntity<Object> response =
                  context
                      .getBean(PlatformProblemHandler.class)
                      .handleNotFound(new dev.platform.starter.error.NotFoundException("o", 1));
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
              response =
                  context.getBean(PlatformProblemHandler.class).handleUnexpected(new Exception());
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            });
  }

  @Test
  void constraint_handler_registered_when_validation_present() {
    runner.run(
        context -> assertThat(context).hasSingleBean(PlatformConstraintViolationHandler.class));
  }
}
