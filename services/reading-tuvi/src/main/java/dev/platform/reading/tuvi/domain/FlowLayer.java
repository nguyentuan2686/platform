package dev.platform.reading.tuvi.domain;

import java.util.List;

/** A flow layer (year, month or day): the natal palace on its branch and its Tu Hoa. */
public record FlowLayer(String palaceKey, String branch, String stem, List<Mutagen> mutagens) {

  /** A star carrying a transformation ({@code Loc, Quyen, Khoa, Ky}) in this layer. */
  public record Mutagen(String star, String mutagen) {}
}
