package dev.platform.__JAVA_PKG__.domain;

/** What the service says about itself. A value object: the name is never blank. */
public record ServiceInfo(String service) {

  public ServiceInfo {
    if (service == null || service.isBlank()) {
      throw new IllegalArgumentException("service name must not be blank");
    }
  }
}
