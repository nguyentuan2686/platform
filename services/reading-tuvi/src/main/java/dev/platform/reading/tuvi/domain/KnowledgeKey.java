package dev.platform.reading.tuvi.domain;

import java.util.regex.Pattern;

/** Knowledge lookup key (spec 6.4); the grammar is enforced on construction. */
public record KnowledgeKey(String value) {

  private static final Pattern GRAMMAR =
      Pattern.compile(
          "^tuvi:(frame=[a-z_]+|daily:area=(CongViec|DiLai|TienBac|SucKhoe):level=(Thuan|Binh|LuuY)"
              + "|[a-z]+=[A-Za-z]+(:[a-z]+=[A-Za-z]+)*)$");

  public KnowledgeKey {
    if (value == null || !GRAMMAR.matcher(value).matches()) {
      throw new IllegalArgumentException("invalid knowledge key");
    }
  }

  public static KnowledgeKey star(String star, String palace, String brightness) {
    return new KnowledgeKey(
        "tuvi:star=" + star + ":palace=" + palace + ":brightness=" + brightness);
  }

  public static KnowledgeKey star(String star, String palace) {
    return new KnowledgeKey("tuvi:star=" + star + ":palace=" + palace);
  }

  public static KnowledgeKey star(String star) {
    return new KnowledgeKey("tuvi:star=" + star);
  }

  public static KnowledgeKey palaceTuhoa(String palace, String mutagen) {
    return new KnowledgeKey("tuvi:palace=" + palace + ":tuhoa=" + mutagen);
  }

  public static KnowledgeKey palaceNoMajor(String palace) {
    return new KnowledgeKey("tuvi:palace=" + palace + ":major=none");
  }

  /** {@code mark} is {@code Tuan} or {@code Triet}. */
  public static KnowledgeKey palaceMark(String palace, String mark) {
    return new KnowledgeKey("tuvi:palace=" + palace + ":mark=" + mark);
  }

  public static KnowledgeKey palace(String palace) {
    return new KnowledgeKey("tuvi:palace=" + palace);
  }

  public static KnowledgeKey daily(Area area, Level level) {
    return new KnowledgeKey("tuvi:daily:area=" + area + ":level=" + level);
  }

  public static KnowledgeKey frame(String id) {
    return new KnowledgeKey("tuvi:frame=" + id);
  }
}
