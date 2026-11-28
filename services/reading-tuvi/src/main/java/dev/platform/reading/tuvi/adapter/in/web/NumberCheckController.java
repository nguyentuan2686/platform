package dev.platform.reading.tuvi.adapter.in.web;

import dev.platform.reading.tuvi.application.number.NumberCheckService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Free number check (xem số) for the web BFF: {@code X-Internal-Token} (platform filter), the
 * user's session as {@code Authorization: Bearer} and the end user's IP as {@code X-Client-Ip}
 * (optional; without it only the per-subject limit applies). The number travels only in the body.
 */
@RestController
public class NumberCheckController {

  private final NumberCheckService service;

  public NumberCheckController(NumberCheckService service) {
    this.service = service;
  }

  @PostMapping("/tuvi/number-check")
  NumberCheckResponse check(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Client-Ip", required = false) String clientIp,
      @RequestBody NumberCheckRequest request) {
    String bearer = Bearer.require(authorization);
    return NumberCheckResponse.from(service.check(request.toCommand(bearer, clientIp)));
  }

  @GetMapping("/tuvi/number-check/quota")
  NumberCheckResponse.Quota quota(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return NumberCheckResponse.Quota.from(service.quota(Bearer.require(authorization)));
  }
}
