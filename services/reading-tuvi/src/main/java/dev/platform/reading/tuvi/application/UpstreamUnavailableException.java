package dev.platform.reading.tuvi.application;

/** The chart service answered with a failure, timed out or could not be reached. */
public class UpstreamUnavailableException extends RuntimeException {

  public UpstreamUnavailableException(String message) {
    super(message);
  }
}
