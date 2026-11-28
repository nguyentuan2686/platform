package dev.platform.reading.tuvi.adapter.in.web;

import dev.platform.reading.tuvi.application.UpstreamUnavailableException;
import dev.platform.reading.tuvi.application.UpstreamValidationException;
import dev.platform.reading.tuvi.application.number.NumberQuotaExceededException;
import dev.platform.reading.tuvi.application.qa.AccountRequiredException;
import dev.platform.reading.tuvi.application.qa.QaConflictException;
import dev.platform.reading.tuvi.application.qa.QaInvalidRequestException;
import dev.platform.reading.tuvi.application.qa.QaNotFoundException;
import dev.platform.reading.tuvi.application.qa.UnauthenticatedException;
import dev.platform.reading.tuvi.domain.number.InvalidNumberException;
import dev.platform.starter.web.PlatformProblemHandler;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * Reading-specific problems. Each one is handed to the platform handler as an {@link
 * ErrorResponseException}, which adds {@code trace_id} and the default {@code type} slug, so the
 * shape stays identical to every other platform problem. Messages never echo request content.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReadingExceptionHandler {

  static final URI UPSTREAM_UNAVAILABLE = URI.create("urn:problem:upstream-unavailable");
  static final URI UNAUTHENTICATED = URI.create("urn:problem:unauthenticated");
  static final URI FORBIDDEN = URI.create("urn:problem:forbidden");
  static final URI CONFLICT = URI.create("urn:problem:conflict");

  private final PlatformProblemHandler platform;

  ReadingExceptionHandler(PlatformProblemHandler platform) {
    this.platform = platform;
  }

  /** Malformed JSON or a value of the wrong type (e.g. a bad date) is reported on {@code body}. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Object> notReadable(HttpMessageNotReadableException ex, WebRequest request)
      throws Exception {
    return validation(
        List.of(Map.of("field", "body", "message", "Malformed request body")), request);
  }

  @ExceptionHandler(UpstreamValidationException.class)
  ResponseEntity<Object> upstreamRejected(UpstreamValidationException ex, WebRequest request)
      throws Exception {
    return validation(
        ex.errors().stream().map(e -> Map.of("field", e.field(), "message", e.message())).toList(),
        request);
  }

  @ExceptionHandler(UpstreamUnavailableException.class)
  ResponseEntity<Object> upstreamUnavailable(UpstreamUnavailableException ex, WebRequest request)
      throws Exception {
    String text =
        "identity".equals(ex.upstream())
            ? "Identity service unavailable"
            : "Chart service unavailable";
    ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, text);
    detail.setType(UPSTREAM_UNAVAILABLE);
    return respond(HttpStatus.SERVICE_UNAVAILABLE, detail, request);
  }

  @ExceptionHandler(QaInvalidRequestException.class)
  ResponseEntity<Object> invalidQa(QaInvalidRequestException ex, WebRequest request)
      throws Exception {
    return validation(
        List.of(Map.of("field", ex.field(), "message", ex.field() + " is invalid")), request);
  }

  @ExceptionHandler(UnauthenticatedException.class)
  ResponseEntity<Object> unauthenticated(UnauthenticatedException ex, WebRequest request)
      throws Exception {
    ProblemDetail detail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required");
    detail.setType(UNAUTHENTICATED);
    return respond(HttpStatus.UNAUTHORIZED, detail, request);
  }

  @ExceptionHandler(AccountRequiredException.class)
  ResponseEntity<Object> accountRequired(AccountRequiredException ex, WebRequest request)
      throws Exception {
    ProblemDetail detail =
        ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "An account is required");
    detail.setType(FORBIDDEN);
    detail.setProperty("reason", "account_required");
    return respond(HttpStatus.FORBIDDEN, detail, request);
  }

  @ExceptionHandler(QaConflictException.class)
  ResponseEntity<Object> conflict(QaConflictException ex, WebRequest request) throws Exception {
    ProblemDetail detail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The request conflicts");
    detail.setType(CONFLICT);
    detail.setProperty("reason", ex.reason());
    return respond(HttpStatus.CONFLICT, detail, request);
  }

  /**
   * A number check that cannot be scored: 400 with {@code reason} {@code invalid_number} (field
   * {@code number}) or {@code invalid_birth} (field {@code birth_year}). Never echoes the input.
   */
  @ExceptionHandler(InvalidNumberException.class)
  ResponseEntity<Object> invalidNumber(InvalidNumberException ex, WebRequest request)
      throws Exception {
    String field = "invalid_birth".equals(ex.reason()) ? "birth_year" : "number";
    ProblemDetail detail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
    detail.setProperty("errors", List.of(Map.of("field", field, "message", field + " is invalid")));
    detail.setProperty("reason", ex.reason());
    return respond(HttpStatus.BAD_REQUEST, detail, request);
  }

  @ExceptionHandler(NumberQuotaExceededException.class)
  ResponseEntity<Object> numberQuota(NumberQuotaExceededException ex, WebRequest request)
      throws Exception {
    ProblemDetail detail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Daily number checks used up");
    detail.setType(CONFLICT);
    detail.setProperty("reason", ex.reason());
    detail.setProperty(
        "quota",
        Map.of(
            "limit", ex.quota().limit(),
            "used", ex.quota().used(),
            "remaining", ex.quota().remaining()));
    return respond(HttpStatus.CONFLICT, detail, request);
  }

  @ExceptionHandler(QaNotFoundException.class)
  ResponseEntity<Object> notFound(QaNotFoundException ex, WebRequest request) throws Exception {
    ProblemDetail detail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Reading not found");
    return respond(HttpStatus.NOT_FOUND, detail, request);
  }

  private ResponseEntity<Object> respond(
      HttpStatus status, ProblemDetail detail, WebRequest request) throws Exception {
    return platform.handleException(new ErrorResponseException(status, detail, null), request);
  }

  private ResponseEntity<Object> validation(List<Map<String, String>> errors, WebRequest request)
      throws Exception {
    ProblemDetail detail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
    detail.setProperty("errors", errors);
    return platform.handleException(
        new ErrorResponseException(HttpStatus.BAD_REQUEST, detail, null), request);
  }
}
