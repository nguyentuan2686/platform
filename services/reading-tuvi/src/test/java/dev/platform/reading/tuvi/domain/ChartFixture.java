package dev.platform.reading.tuvi.domain;

import dev.platform.reading.tuvi.domain.FlowLayer.Mutagen;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Test helper: builds domain records from the chart-svc-shaped JSON fixture (tests only). */
public final class ChartFixture {

  private ChartFixture() {}

  public static Chart load() {
    try (InputStream in = ChartFixture.class.getResourceAsStream("/chart-fixture.json")) {
      JsonNode root = JsonMapper.builder().build().readTree(in);
      List<Palace> palaces = new ArrayList<>();
      for (JsonNode p : root.get("palaces")) {
        palaces.add(
            new Palace(
                p.get("key").asString(),
                p.get("nameVi").asString(),
                p.get("branch").asString(),
                p.get("isBody").asBoolean(),
                stars(p.get("major")),
                stars(p.get("minor")),
                p.get("tuan").asBoolean(),
                p.get("triet").asBoolean()));
      }
      return new Chart(
          root.get("cuc").get("nameVi").asString(),
          root.get("thanPalace").asString(),
          strings(root.get("tuan")),
          strings(root.get("triet")),
          palaces);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public static FlowLayer layer(String palaceKey, Mutagen... mutagens) {
    return new FlowLayer(palaceKey, "Zi", "Jia", List.of(mutagens));
  }

  private static List<StarPlacement> stars(JsonNode arr) {
    List<StarPlacement> out = new ArrayList<>();
    for (JsonNode s : arr) {
      out.add(
          new StarPlacement(
              s.get("key").asString(),
              s.get("nameVi").asString(),
              s.get("brightness").isNull() ? null : s.get("brightness").asString(),
              s.get("mutagen").isNull() ? null : s.get("mutagen").asString()));
    }
    return out;
  }

  private static List<String> strings(JsonNode arr) {
    List<String> out = new ArrayList<>();
    arr.forEach(n -> out.add(n.asString()));
    return out;
  }
}
