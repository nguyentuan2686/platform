package dev.platform.reading.tuvi.adapter.in.web;

import dev.platform.reading.tuvi.application.UpstreamUnavailableException;
import dev.platform.reading.tuvi.application.UpstreamValidationException;
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
    ProblemDetail detail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE, "Chart service unavailable");
    detail.setType(UPSTREAM_UNAVAILABLE);
    return platform.handleException(
        new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE, detail, null), request);
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
