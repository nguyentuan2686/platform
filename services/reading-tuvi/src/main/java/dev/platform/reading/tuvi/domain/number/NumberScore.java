package dev.platform.reading.tuvi.domain.number;

import java.util.List;

/** Result of scoring a number against a profile: total out of 10 and the six criteria. */
public record NumberScore(
    double total,
    Band band,
    List<Criterion> criteria,
    Hexagram hexagram,
    Element numberElement,
    List<String> goodPairs,
    List<String> avoidPairs) {

  public NumberScore {
    criteria = List.copyOf(criteria);
    goodPairs = List.copyOf(goodPairs);
    avoidPairs = List.copyOf(avoidPairs);
  }
}
