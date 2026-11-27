package dev.platform.__JAVA_PKG__.application;

import dev.platform.__JAVA_PKG__.domain.ServiceInfo;

/** Framework-free application service; wired in {@code adapter.config}. */
public class InfoService implements GetInfoUseCase {

  private final ServiceInfo info;

  public InfoService(String serviceName) {
    this.info = new ServiceInfo(serviceName);
  }

  @Override
  public ServiceInfo info() {
    return info;
  }
}
