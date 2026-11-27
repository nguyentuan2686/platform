package dev.platform.identity.adapter.in.web;

import dev.platform.identity.application.AccountNotFoundException;
import dev.platform.identity.application.ConsumptionNotFoundException;
import dev.platform.identity.application.CreditConflictException;
import dev.platform.identity.application.ForbiddenException;
import dev.platform.identity.application.InvalidRedirectUriException;
import dev.platform.identity.application.MailCapReachedException;
import dev.platform.identity.application.MailDeliveryException;
import dev.platform.identity.application.NoProfileException;
import dev.platform.identity.application.OidcUnavailableException;
import dev.platform.identity.application.ProfileNotFoundException;
import dev.platform.identity.application.UnauthenticatedException;
import dev.platform.identity.domain.ProfileLockedException;
import dev.platform.identity.domain.ProfileValidationException;
import dev.platform.starter.telemetry.PlatformMeter;
import dev.platform.starter.web.PlatformProblemHandler;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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

  private static final Logger log = LoggerFactory.getLogger(IdentityExceptionHandler.class);

  private final PlatformProblemHandler platform;
  private final LongCounter mailCapCounter;

  IdentityExceptionHandler(PlatformProblemHandler platform, ObjectProvider<OpenTelemetry> otel) {
    this.platform = platform;
    // Counter identity.magic_link.daily_cap_reached (alert mail-cap-reached). Recorded once at 0 so
    // the first refusal is an increase Prometheus can see, not a new series.
    this.mailCapCounter =
        PlatformMeter.of(otel)
            .counterBuilder("identity.magic_link.daily_cap_reached")
            .setDescription("Magic-link requests refused by the global daily mail cap")
            .build();
    this.mailCapCounter.add(0);
  }

  /** The global daily mail cap: an open refusal (it reveals nothing about the address). */
  @ExceptionHandler(MailCapReachedException.class)
  ResponseEntity<Object> mailCapReached(MailCapReachedException ex, WebRequest request)
      throws Exception {
    mailCapCounter.add(1);
    log.warn("magic link refused: daily mail cap reached");
    ProblemDetail detail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.TOO_MANY_REQUESTS, "Too many login mails today, try again later");
    detail.setType(URI.create("urn:problem:rate-limited"));
    detail.setProperty("reason", "mail_daily_cap");
    return platform.handleException(
        new ErrorResponseException(HttpStatus.TOO_MANY_REQUESTS, detail, null), request);
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

  @ExceptionHandler(InvalidRedirectUriException.class)
  ResponseEntity<Object> invalidRedirect(InvalidRedirectUriException ex, WebRequest request)
      throws Exception {
    ProblemDetail detail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
    detail.setProperty(
        "errors",
        List.of(Map.of("field", "redirect_uri", "message", "redirect_uri is not allowed")));
    return platform.handleException(
        new ErrorResponseException(HttpStatus.BAD_REQUEST, detail, null), request);
  }

  @ExceptionHandler(OidcUnavailableException.class)
  ResponseEntity<Object> oidcUnavailable(OidcUnavailableException ex, WebRequest request)
      throws Exception {
    ProblemDetail detail =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    detail.setType(URI.create("urn:problem:upstream-unavailable"));
    return platform.handleException(
        new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE, detail, null), request);
  }

  @ExceptionHandler(ProfileLockedException.class)
  ResponseEntity<Object> profileLocked(ProfileLockedException ex, WebRequest request)
      throws Exception {
    ProblemDetail detail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Birth data is locked");
    detail.setType(URI.create("urn:problem:conflict"));
    detail.setProperty("reason", "profile_locked");
    return platform.handleException(
        new ErrorResponseException(HttpStatus.CONFLICT, detail, null), request);
  }

  /** Never echoes the offending value: only the field name and a fixed rule. */
  @ExceptionHandler(ProfileValidationException.class)
  ResponseEntity<Object> invalidProfile(ProfileValidationException ex, WebRequest request)
      throws Exception {
    ProblemDetail detail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
    detail.setProperty("errors", List.of(Map.of("field", ex.field(), "message", ex.getMessage())));
    return platform.handleException(
        new ErrorResponseException(HttpStatus.BAD_REQUEST, detail, null), request);
  }

  @ExceptionHandler(ProfileNotFoundException.class)
  ResponseEntity<Object> profileNotFound(ProfileNotFoundException ex, WebRequest request)
      throws Exception {
    ProblemDetail detail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Profile not found");
    return platform.handleException(
        new ErrorResponseException(HttpStatus.NOT_FOUND, detail, null), request);
  }

  @ExceptionHandler(NoProfileException.class)
  ResponseEntity<Object> noProfile(NoProfileException ex, WebRequest request) throws Exception {
    ProblemDetail detail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Save a chart profile first");
    detail.setType(URI.create("urn:problem:conflict"));
    detail.setProperty("reason", "no_profile");
    return platform.handleException(
        new ErrorResponseException(HttpStatus.CONFLICT, detail, null), request);
  }

  @ExceptionHandler(ForbiddenException.class)
  ResponseEntity<Object> forbidden(ForbiddenException ex, WebRequest request) throws Exception {
    ProblemDetail detail =
        ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Not allowed for this session");
    detail.setType(URI.create("urn:problem:forbidden"));
    return platform.handleException(
        new ErrorResponseException(HttpStatus.FORBIDDEN, detail, null), request);
  }

  @ExceptionHandler(CreditConflictException.class)
  ResponseEntity<Object> creditConflict(CreditConflictException ex, WebRequest request)
      throws Exception {
    ProblemDetail detail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Credit operation refused");
    detail.setType(URI.create("urn:problem:conflict"));
    detail.setProperty("reason", ex.reason());
    return platform.handleException(
        new ErrorResponseException(HttpStatus.CONFLICT, detail, null), request);
  }

  @ExceptionHandler({AccountNotFoundException.class, ConsumptionNotFoundException.class})
  ResponseEntity<Object> creditNotFound(RuntimeException ex, WebRequest request) throws Exception {
    ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    return platform.handleException(
        new ErrorResponseException(HttpStatus.NOT_FOUND, detail, null), request);
  }

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
