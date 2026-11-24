package dev.platform.reading.tuvi.adapter.in.web;

import dev.platform.reading.tuvi.application.BasicReadingUseCase;
import dev.platform.reading.tuvi.application.TodayUseCase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReadingController {

  private final BasicReadingUseCase basic;
  private final TodayUseCase today;

  public ReadingController(BasicReadingUseCase basic, TodayUseCase today) {
    this.basic = basic;
    this.today = today;
  }

  @PostMapping("/tuvi/readings/basic")
  BasicReadingResponse basic(@RequestBody BirthRequest request) {
    return BasicReadingResponse.from(basic.basic(request.toBirth()));
  }

  @PostMapping("/tuvi/readings/today")
  TodayResponse today(@Valid @RequestBody TodayRequest request) {
    return TodayResponse.from(today.today(request.toBirth(), request.date()));
  }
}
