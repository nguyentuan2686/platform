package dev.platform.payment.adapter.in.web;

import dev.platform.payment.application.ConflictException;
import dev.platform.payment.application.OrderValidationException;
import dev.platform.payment.application.PaymentNotConfiguredException;
import dev.platform.payment.application.UpstreamUnavailableException;
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
 * Payment-specific problems, handed to the platform handler so that trace_id and the problem shape
 * match every other platform problem. Messages never echo request content.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PaymentExceptionHandler {

  private final PlatformProblemHandler platform;

  PaymentExceptionHandler(PlatformProblemHandler platform) {
    this.platform = platform;
  }

  @ExceptionHandler(OrderValidationException.class)
  ResponseEntity<Object> invalid(OrderValidationException ex, WebRequest request) throws Exception {
    return validation(ex.field(), ex.getMessage(), request);
  }

  /** Malformed JSON or a value of the wrong type is reported on body. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Object> notReadable(HttpMessageNotReadableException ex, WebRequest request)
      throws Exception {
    return validation("body", "Malformed request body", request);
  }

  @ExceptionHandler(PaymentNotConfiguredException.class)
  ResponseEntity<Object> notConfigured(PaymentNotConfiguredException ex, WebRequest request)
      throws Exception {
    ProblemDetail detail =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    detail.setType(URI.create("urn:problem:upstream-unavailable"));
    return platform.handleException(
        new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE, detail, null), request);
  }

  @ExceptionHandler(ConflictException.class)
  ResponseEntity<Object> conflict(ConflictException ex, WebRequest request) throws Exception {
    ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Request refused");
    detail.setType(URI.create("urn:problem:conflict"));
    detail.setProperty("reason", ex.reason());
    return platform.handleException(
        new ErrorResponseException(HttpStatus.CONFLICT, detail, null), request);
  }

  @ExceptionHandler(UpstreamUnavailableException.class)
  ResponseEntity<Object> upstream(UpstreamUnavailableException ex, WebRequest request)
      throws Exception {
    ProblemDetail detail =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable");
    detail.setType(URI.create("urn:problem:upstream-unavailable"));
    return platform.handleException(
        new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE, detail, null), request);
  }

  /** A bare 401: no hint about which credential was wrong. */
  @ExceptionHandler(UnauthenticatedException.class)
  ResponseEntity<Object> unauthenticated(UnauthenticatedException ex, WebRequest request)
      throws Exception {
    ProblemDetail detail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required");
    detail.setType(URI.create("urn:problem:unauthenticated"));
    return platform.handleException(
        new ErrorResponseException(HttpStatus.UNAUTHORIZED, detail, null), request);
  }

  private ResponseEntity<Object> validation(String field, String message, WebRequest request)
      throws Exception {
    ProblemDetail detail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
    detail.setProperty("errors", List.of(Map.of("field", field, "message", message)));
    return platform.handleException(
        new ErrorResponseException(HttpStatus.BAD_REQUEST, detail, null), request);
  }
}
