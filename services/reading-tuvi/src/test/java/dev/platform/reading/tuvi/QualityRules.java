package dev.platform.reading.tuvi;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
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

  private static final Set<String> BANNED =
      Set.of(
          "chết",
          "tử vong",
          "tai nạn",
          "ung thư",
          "bệnh nặng",
          "giải hạn",
          "cúng",
          "bùa",
          "yểm",
          "phá sản",
          "ly hôn",
          "tuyệt tự",
          "đoản mệnh",
          "yểu",
          "suốt đời",
          "chắc chắn sẽ",
          "không tránh khỏi");

  private static final Pattern SENTENCE_END = Pattern.compile("[.!?…]+(\\s+|$)");
  private static final Pattern DIACRITIC =
      Pattern.compile(
          "[àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ]",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern VOICE =
      Pattern.compile("(?<!\\p{L})con(?!\\p{L})", Pattern.CASE_INSENSITIVE);
  private static final Pattern SOURCE_REF =
      Pattern.compile("^(derived:)?ziwei-quanshu:juan[123]:.+");
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
    if (content.codePoints().anyMatch(QualityRules::isCjk)) {
      out.add(v("cjk", key));
    }
    if (BANNED.stream().anyMatch(lower::contains)) {
      out.add(v("banned", key));
    }
    if (!VOICE.matcher(content).find()) {
      out.add(v("voice", key));
    }
    if (!SOURCE_REF.matcher(str(r, "source_ref")).matches()) {
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

  private static boolean isCjk(int cp) {
    return (cp >= 0x3400 && cp <= 0x9FFF) || (cp >= 0xF900 && cp <= 0xFAFF);
  }

  private static String str(JsonNode r, String field) {
    JsonNode n = r.get(field);
    return n == null || n.isNull() ? "" : n.asString();
  }

  private static String v(String rule, String key) {
    return "rule=" + rule + " key=" + key;
  }
}
