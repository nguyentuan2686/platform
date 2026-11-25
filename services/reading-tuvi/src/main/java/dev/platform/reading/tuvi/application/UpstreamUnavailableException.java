package dev.platform.reading.tuvi.application;

/**
 * A service this one depends on answered with a failure, timed out or could not be reached. {@code
 * upstream} is {@code chart} or {@code identity}; it picks the problem detail.
 */
public class UpstreamUnavailableException extends RuntimeException {

  private final String upstream;

  public UpstreamUnavailableException(String upstream) {
    super(upstream + " unavailable");
    this.upstream = upstream;
  }

  public String upstream() {
    return upstream;
  }
}
