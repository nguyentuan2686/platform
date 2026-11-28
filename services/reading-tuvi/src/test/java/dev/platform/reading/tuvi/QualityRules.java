package dev.platform.reading.tuvi;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.platform.reading.tuvi.domain.SafetyLexicon;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Content rules for doctrine records (Plan F1.2b, Global Constraints). A violation is reported as
 * {@code rule=<name> key=<key>}; the record content is never echoed.
 */
final class QualityRules {

  private static final Set<String> BANNED = SafetyLexicon.BANNED;

  private static final Pattern SENTENCE_END = Pattern.compile("[.!?…]+(\\s+|$)");
  private static final Pattern DIACRITIC =
      Pattern.compile(
          "[àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ]",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern VOICE =
      Pattern.compile("(?<!\\p{L})con(?!\\p{L})", Pattern.CASE_INSENSITIVE);
  private static final Pattern REF = Pattern.compile("^juan([123]):(.+)$");
  private static final Pattern HEADING = Pattern.compile("^=+([^=]+)=+$");
  private static final Pattern P2_KEY =
      Pattern.compile("^tuvi:star=([A-Za-z]+):palace=([A-Za-z]+):brightness=[A-Za-z]+$");

  private static final Map<String, String> PALACE_TOPIC =
      Map.ofEntries(
          Map.entry("Menh", "tinh-cach"),
          Map.entry("PhucDuc", "tinh-cach"),
          Map.entry("ThienDi", "tinh-cach"),
          Map.entry("PhuThe", "tinh-duyen"),
          Map.entry("QuanLoc", "su-nghiep"),
          Map.entry("NoBoc", "su-nghiep"),
          Map.entry("TaiBach", "tai-loc"),
          Map.entry("DienTrach", "tai-loc"),
          Map.entry("TatAch", "suc-khoe"),
          Map.entry("PhuMau", "van-han"),
          Map.entry("HuynhDe", "van-han"),
          Map.entry("TuTuc", "van-han"));
  private static final Pattern PALACE_IN_KEY = Pattern.compile("palace=([A-Za-z]+)");

  /** Number-check readings (spec §6) are original copy, not doctrine from the source book. */
  private static final String NUMBER_SOURCE = "original:number";

  private static final Schema SCHEMA = loadSchema();

  private QualityRules() {}

  private static Schema loadSchema() {
    try {
      Path file = Path.of("..", "..", "contracts", "knowledge", "tuvi-record.schema.json");
      return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
          .getSchema(Files.readString(file));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Per-record rules. */
  static List<String> check(JsonNode r) {
    List<String> out = new ArrayList<>();
    String key = str(r, "key");
    if (!SCHEMA.validate(r).isEmpty()) {
      out.add(v("schema", key));
    }
    if (!"iztro-default".equals(str(r, "school"))) {
      out.add(v("school", key));
    }
    if (!"vi".equals(str(r, "lang"))) {
      out.add(v("lang", key));
    }
    String content = Normalizer.normalize(str(r, "content"), Normalizer.Form.NFC);
    String lower = content.toLowerCase(Locale.ROOT);
    int sentences = 0;
    for (String part : SENTENCE_END.split(content)) {
      if (!part.isBlank()) {
        sentences++;
      }
    }
    if (sentences < 1 || sentences > 3) {
      out.add(v("sentences", key));
    }
    if (content.length() > 320) {
      out.add(v("length", key));
    }
    if (!DIACRITIC.matcher(content).find()) {
      out.add(v("diacritic", key));
    }
    if (content.codePoints().anyMatch(SafetyLexicon::isCjk)) {
      out.add(v("cjk", key));
    }
    if (BANNED.stream().anyMatch(lower::contains)) {
      out.add(v("banned", key));
    }
    if (!VOICE.matcher(content).find()) {
      out.add(v("voice", key));
    }
    boolean sourceOk =
        key.startsWith("so:")
            ? NUMBER_SOURCE.equals(str(r, "source_ref"))
            : refs(str(r, "source_ref")) != null;
    if (!sourceOk) {
      out.add(v("source_ref", key));
    }
    String expected = expectedTopic(key);
    if (expected != null && !expected.equals(str(r, "topic"))) {
      out.add(v("topic", key));
    }
    return out;
  }

  /** Per-record rules plus the rules that need the whole set (duplicates, P2 parents). */
  static List<String> checkAll(List<JsonNode> records) {
    List<String> out = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    records.forEach(r -> keys.add(str(r, "key")));
    Map<String, String> byContent = new HashMap<>();
    for (JsonNode r : records) {
      out.addAll(check(r));
      String key = str(r, "key");
      String prior = byContent.putIfAbsent(str(r, "content"), key);
      if (prior != null) {
        out.add(v("duplicate-content", key));
      }
      var m = P2_KEY.matcher(key);
      if (m.matches() && !keys.contains("tuvi:star=" + m.group(1) + ":palace=" + m.group(2))) {
        out.add(v("p2-parent", key));
      }
    }
    return out;
  }

  /** Topic by palace (or tinh-cach for a bare star key); null when the key family has no rule. */
  private static String expectedTopic(String key) {
    var m = PALACE_IN_KEY.matcher(key);
    if (m.find()) {
      return PALACE_TOPIC.get(m.group(1));
    }
    return key.matches("^tuvi:star=[A-Za-z]+$") ? "tinh-cach" : null;
  }

  /** Cited source headings that do not appear as a heading line in the source text. */
  static List<String> missingHeadings(List<JsonNode> records, Path sourcesDir) {
    Map<String, Set<String>> headings = new HashMap<>();
    List<String> out = new ArrayList<>();
    for (JsonNode r : records) {
      List<String> refs = refs(str(r, "source_ref"));
      if (refs == null) {
        continue; // reported by the source_ref rule
      }
      for (String ref : refs) {
        var m = REF.matcher(ref);
        if (m.matches()
            && !headings
                .computeIfAbsent(m.group(1), j -> headingsOf(sourcesDir, j))
                .contains(m.group(2))) {
          out.add(v("source_heading", str(r, "key")));
          break;
        }
      }
    }
    return out;
  }

  /**
   * The {@code juan<N>:<heading>} parts of a source ref, or null when it is malformed. Accepted:
   * {@code ziwei-quanshu:juan<N>:<heading>}, or {@code derived:ziwei-quanshu:juan<N>:<heading>}
   * followed by any number of {@code ,juan<N>:<heading>} (every part is checked).
   */
  static List<String> refs(String sourceRef) {
    boolean derived = sourceRef.startsWith("derived:");
    String rest = derived ? sourceRef.substring("derived:".length()) : sourceRef;
    if (!rest.startsWith("ziwei-quanshu:")) {
      return null;
    }
    rest = rest.substring("ziwei-quanshu:".length());
    List<String> parts = derived ? List.of(rest.split(",", -1)) : List.of(rest);
    for (String p : parts) {
      if (!REF.matcher(p).matches()) {
        return null;
      }
    }
    return parts;
  }

  /** Heading texts of one juan: lines framed by {@code =} signs, e.g. {@code ===五财帛===}. */
  private static Set<String> headingsOf(Path sourcesDir, String juan) {
    try {
      Set<String> out = new HashSet<>();
      for (String line : Files.readAllLines(sourcesDir.resolve("juan" + juan + ".txt"))) {
        var m = HEADING.matcher(line.strip());
        if (m.matches()) {
          out.add(m.group(1).strip());
        }
      }
      return out;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Number of records whose content starts with {@code prefix} (NFC). */
  static long countOpening(List<JsonNode> records, String prefix) {
    return records.stream()
        .filter(
            r -> Normalizer.normalize(str(r, "content"), Normalizer.Form.NFC).startsWith(prefix))
        .count();
  }

  private static String str(JsonNode r, String field) {
    JsonNode n = r.get(field);
    return n == null || n.isNull() ? "" : n.asString();
  }

  private static String v(String rule, String key) {
    return "rule=" + rule + " key=" + key;
  }
}
