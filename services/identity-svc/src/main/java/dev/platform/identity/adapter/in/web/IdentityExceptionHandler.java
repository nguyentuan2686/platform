package dev.platform.identity.adapter.in.web;

import dev.platform.identity.application.MailDeliveryException;
import dev.platform.identity.application.UnauthenticatedException;
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
 * Identity-specific problems, handed to the platform handler so that {@code trace_id} and the
 * problem shape match every other platform problem. Messages never echo request content.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IdentityExceptionHandler {

  static final URI UNAUTHENTICATED = URI.create("urn:problem:unauthenticated");

  private final PlatformProblemHandler platform;

  IdentityExceptionHandler(PlatformProblemHandler platform) {
    this.platform = platform;
  }

  @ExceptionHandler(UnauthenticatedException.class)
  ResponseEntity<Object> unauthenticated(UnauthenticatedException ex, WebRequest request)
      throws Exception {
    ProblemDetail detail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required");
    detail.setType(UNAUTHENTICATED);
    return platform.handleException(
        new ErrorResponseException(HttpStatus.UNAUTHORIZED, detail, null), request);
  }

  /** Malformed JSON or a value of the wrong type is reported on {@code body}. */
  @ExceptionHandler(MailDeliveryException.class)
  ResponseEntity<Object> mailFailed(MailDeliveryException ex, WebRequest request) throws Exception {
    ProblemDetail detail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE, "Could not send the mail, try again later");
    detail.setType(URI.create("urn:problem:upstream-unavailable"));
    return platform.handleException(
        new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE, detail, null), request);
  }

  /** Malformed JSON or a value of the wrong type is reported on {@code body}. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Object> notReadable(HttpMessageNotReadableException ex, WebRequest request)
      throws Exception {
    ProblemDetail detail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
    detail.setProperty(
        "errors", List.of(Map.of("field", "body", "message", "Malformed request body")));
    return platform.handleException(
        new ErrorResponseException(HttpStatus.BAD_REQUEST, detail, null), request);
  }
}
