package dev.platform.starter.web;

import dev.platform.starter.error.NotFoundException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Maps every failure to an RFC 9457 {@code application/problem+json} response with a {@code
 * urn:problem:<slug>} type and a {@code trace_id} property (from the current OpenTelemetry span,
 * else the MDC, else empty string; see {@link TraceIds}). Validation failures add an {@code errors}
 * list of {@code {field, message}}. Unexpected exceptions yield a generic 500 whose details are
 * logged server side only.
 */
@RestControllerAdvice
public class PlatformProblemHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(PlatformProblemHandler.class);

  static final String TRACE_ID = "trace_id";
  static final String ERRORS = "errors";
  private static final URI ABOUT_BLANK = URI.create("about:blank");

  @ExceptionHandler(NotFoundException.class)
  ResponseEntity<Object> handleNotFound(NotFoundException ex) {
    log.debug("Not found: {}", ex.getMessage());
    return respond(problem(HttpStatus.NOT_FOUND, ex.getMessage()));
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

  static ResponseEntity<Object> respond(ProblemDetail detail) {
    return ResponseEntity.status(detail.getStatus())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(detail);
  }

  static ProblemDetail validationProblem(List<Map<String, String>> errors) {
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
    // Spring Framework 7 leaves the type null (rendered as absent) instead of about:blank.
    URI type = detail.getType();
    if (slug != null && (type == null || ABOUT_BLANK.equals(type))) {
      detail.setType(URI.create("urn:problem:" + slug));
    }
    detail.setProperty(TRACE_ID, TraceIds.current());
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

  static Map<String, String> error(String field, @Nullable String message) {
    return Map.of("field", field, "message", message == null ? "" : message);
  }

  private static String fieldOf(ObjectError error) {
    return error instanceof FieldError fe ? fe.getField() : error.getObjectName();
  }
}
