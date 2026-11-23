package dev.platform.starter.web;

import dev.platform.starter.error.NotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every failure to an RFC 9457 {@code application/problem+json} response carrying a {@code
 * trace_id} property (taken from the MDC, empty string if absent). Validation failures add an
 * {@code errors} list of {@code {field, message}}. Unexpected exceptions yield a generic 500 whose
 * details are logged server side only.
 */
@RestControllerAdvice
public class PlatformProblemHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(PlatformProblemHandler.class);

  static final String TRACE_ID = "trace_id";
  static final String ERRORS = "errors";

  @ExceptionHandler(NotFoundException.class)
  ResponseEntity<Object> handleNotFound(NotFoundException ex) {
    log.debug("Not found: {}", ex.getMessage());
    return respond(problem(HttpStatus.NOT_FOUND, ex.getMessage()));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex) {
    List<Map<String, String>> errors = new ArrayList<>();
    for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
      errors.add(error(violation.getPropertyPath().toString(), violation.getMessage()));
    }
    log.debug("Constraint violation: {} error(s)", errors.size());
    return respond(validationProblem(errors));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> handleUnexpected(Exception ex) {
    log.error("Unexpected error", ex);
    return respond(problem(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error"));
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<Map<String, String>> errors = new ArrayList<>();
    for (ObjectError error : ex.getBindingResult().getAllErrors()) {
      errors.add(error(fieldOf(error), error.getDefaultMessage()));
    }
    log.debug("Validation failed: {} error(s)", errors.size());
    return respond(validationProblem(errors));
  }

  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<Map<String, String>> errors = new ArrayList<>();
    ex.getParameterValidationResults()
        .forEach(
            result -> {
              String parameter = result.getMethodParameter().getParameterName();
              for (MessageSourceResolvable error : result.getResolvableErrors()) {
                String field = error instanceof FieldError fe ? fe.getField() : parameter;
                errors.add(error(field, error.getDefaultMessage()));
              }
            });
    log.debug("Method validation failed: {} error(s)", errors.size());
    return respond(validationProblem(errors));
  }

  /** Every framework-handled exception funnels through here: enrich with trace_id and type. */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex,
      @Nullable Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    if (statusCode.is5xxServerError()) {
      log.error("Request failed with status {}", statusCode.value(), ex);
    } else {
      log.debug("Request rejected with status {}: {}", statusCode.value(), ex.toString());
    }
    ResponseEntity<Object> response =
        super.handleExceptionInternal(ex, body, headers, statusCode, request);
    if (response != null && response.getBody() instanceof ProblemDetail detail) {
      enrich(detail);
    }
    return response;
  }

  private static ResponseEntity<Object> respond(ProblemDetail detail) {
    return ResponseEntity.status(detail.getStatus())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(detail);
  }

  private static ProblemDetail validationProblem(List<Map<String, String>> errors) {
    ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, "Request validation failed");
    detail.setProperty(ERRORS, errors);
    return detail;
  }

  private static ProblemDetail problem(HttpStatus status, String message) {
    ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
    enrich(detail);
    return detail;
  }

  private static void enrich(ProblemDetail detail) {
    String slug = slug(detail.getStatus());
    if (slug != null && URI.create("about:blank").equals(detail.getType())) {
      detail.setType(URI.create("urn:problem:" + slug));
    }
    String traceId = MDC.get(TRACE_ID);
    detail.setProperty(TRACE_ID, traceId == null ? "" : traceId);
  }

  @Nullable
  private static String slug(int status) {
    return switch (status) {
      case 400 -> "validation-error";
      case 404 -> "not-found";
      case 409 -> "conflict";
      case 503 -> "dependency-unavailable";
      default -> status >= 500 ? "internal-error" : null;
    };
  }

  private static Map<String, String> error(String field, @Nullable String message) {
    return Map.of("field", field, "message", message == null ? "" : message);
  }

  private static String fieldOf(ObjectError error) {
    return error instanceof FieldError fe ? fe.getField() : error.getObjectName();
  }
}
