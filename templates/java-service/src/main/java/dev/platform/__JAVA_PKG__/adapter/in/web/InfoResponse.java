package dev.platform.__JAVA_PKG__.adapter.in.web;

import dev.platform.__JAVA_PKG__.domain.ServiceInfo;

public record InfoResponse(String service) {

  static InfoResponse from(ServiceInfo info) {
    return new InfoResponse(info.service());
  }
}
