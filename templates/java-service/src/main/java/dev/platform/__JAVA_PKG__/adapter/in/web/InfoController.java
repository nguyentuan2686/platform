package dev.platform.__JAVA_PKG__.adapter.in.web;

import dev.platform.__JAVA_PKG__.application.GetInfoUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InfoController {

  private final GetInfoUseCase getInfo;

  public InfoController(GetInfoUseCase getInfo) {
    this.getInfo = getInfo;
  }

  @GetMapping("/v1/info")
  InfoResponse info() {
    return InfoResponse.from(getInfo.info());
  }
}
