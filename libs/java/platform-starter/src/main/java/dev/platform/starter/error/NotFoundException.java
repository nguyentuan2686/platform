package dev.platform.starter.error;

/**
 * Thrown by application code when a requested resource does not exist. Deliberately free of any web
 * dependency; the web layer maps it to a 404 problem response.
 */
public class NotFoundException extends RuntimeException {

  private final String resource;
  private final transient Object id;

  public NotFoundException(String resource, Object id) {
    super(resource + " " + id + " not found");
    this.resource = resource;
    this.id = id;
  }

  public String resource() {
    return resource;
  }

  public Object id() {
    return id;
  }
}
