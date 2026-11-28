package dev.platform.reading.tuvi.domain.number;

import java.util.ArrayList;
import java.util.List;

/**
 * The knowledge keys of a number reading, in the order of spec §6: intro, mệnh, ngũ hành, quẻ, âm
 * dương, cung phi, nút, up to three folk pairs (good first), band, closing.
 */
public final class NumberReadingPlan {

  static final int MAX_PAIRS = 3;

  private NumberReadingPlan() {}

  public static List<String> keys(NumberInput in, Profile p, NumberScore s) {
    List<String> out = new ArrayList<>();
    out.add("so:intro=" + in.kind().key());
    out.add("so:menh=" + p.element().key());
    for (Criterion c : s.criteria()) {
      if (c.readingKey() != null) {
        out.add(c.readingKey());
      }
    }
    List<String> pairs = new ArrayList<>(s.goodPairs());
    pairs.addAll(s.avoidPairs());
    pairs.stream().limit(MAX_PAIRS).forEach(x -> out.add("so:pair=" + x));
    out.add("so:band=" + s.band().key());
    out.add("so:closing");
    return List.copyOf(out);
  }
}
