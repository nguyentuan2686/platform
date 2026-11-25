package dev.platform.reading.tuvi.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.platform.reading.tuvi.application.qa.QaMergeUseCase;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service: moves a guest's Q&A history onto an account (internal token only). */
@RestController
public class QaMergeController {

  public record MergeResult(@JsonProperty("moved") int moved) {}

  private final QaMergeUseCase merge;

  public QaMergeController(QaMergeUseCase merge) {
    this.merge = merge;
  }

  @PostMapping("/internal/qa/merge")
  MergeResult merge(@RequestBody MergeRequest request) {
    return new MergeResult(merge.merge(request.guest(), request.account()));
  }
}
