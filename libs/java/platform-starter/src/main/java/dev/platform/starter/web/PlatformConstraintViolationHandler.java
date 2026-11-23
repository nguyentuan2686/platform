package dev.platform.starter.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps {@link ConstraintViolationException} to a 400 problem. Kept separate from {@link
 * PlatformProblemHandler} so the latter loads without jakarta.validation on the classpath.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PlatformConstraintViolationHandler {

  private static final Logger log =
      LoggerFactory.getLogger(PlatformConstraintViolationHandler.class);

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex) {
    List<Map<String, String>> errors = new ArrayList<>();
    for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
      errors.add(
          PlatformProblemHandler.error(
              violation.getPropertyPath().toString(), violation.getMessage()));
    }
    log.debug("Constraint violation: {} error(s)", errors.size());
    return PlatformProblemHandler.respond(PlatformProblemHandler.validationProblem(errors));
  }
}
