package dev.platform.payment.adapter.in.web;

/** A webhook or admin call without valid credentials; maps to a bare 401. */
public class UnauthenticatedException extends RuntimeException {

  public UnauthenticatedException() {
    super("unauthenticated");
  }
}
