package dev.platform.reading.tuvi.domain;

import dev.platform.reading.tuvi.domain.number.Band;
import dev.platform.reading.tuvi.domain.number.Element;
import dev.platform.reading.tuvi.domain.number.FolkPairs;
import dev.platform.reading.tuvi.domain.number.NumberKind;
import java.util.ArrayList;
import java.util.List;

/**
 * The closed set of knowledge keys the reading can ask for (Plan F1.2b). P1 keys are written for
 * every combination; P2 (star x palace x brightness) only for the palaces in {@link #P2_PALACES}.
 * The value lists mirror the chart-svc contract enums (checked by a test).
 */
public final class KeyCatalog {

  public static final List<String> MAJOR_STARS =
      List.of(
          "TuVi",
          "ThienCo",
          "ThaiDuong",
          "VuKhuc",
          "ThienDong",
          "LiemTrinh",
          "ThienPhu",
          "ThaiAm",
          "ThamLang",
          "CuMon",
          "ThienTuong",
          "ThienLuong",
          "ThatSat",
          "PhaQuan");

  public static final List<String> PALACES =
      List.of(
          "Menh",
          "PhuMau",
          "PhucDuc",
          "DienTrach",
          "QuanLoc",
          "NoBoc",
          "ThienDi",
          "TatAch",
          "TaiBach",
          "TuTuc",
          "PhuThe",
          "HuynhDe");

  public static final List<String> BRIGHTNESS = List.of("Mieu", "Vuong", "Dac", "Binh", "Ham");

  public static final List<String> MUTAGENS = List.of("Loc", "Quyen", "Khoa", "Ky");

  public static final List<String> MARKS = List.of("Tuan", "Triet");

  public static final List<String> P2_PALACES = List.of("Menh", "QuanLoc", "TaiBach", "PhuThe");

  private KeyCatalog() {}

  /** 14 stars + 12 palaces + 12 no-major + 24 marks + 48 tu hoa + 168 star-in-palace = 278. */
  public static List<KnowledgeKey> p1() {
    List<KnowledgeKey> out = new ArrayList<>();
    MAJOR_STARS.forEach(s -> out.add(KnowledgeKey.star(s)));
    PALACES.forEach(p -> out.add(KnowledgeKey.palace(p)));
    PALACES.forEach(p -> out.add(KnowledgeKey.palaceNoMajor(p)));
    for (String p : PALACES) {
      MARKS.forEach(m -> out.add(KnowledgeKey.palaceMark(p, m)));
    }
    for (String p : PALACES) {
      MUTAGENS.forEach(m -> out.add(KnowledgeKey.palaceTuhoa(p, m)));
    }
    for (String s : MAJOR_STARS) {
      PALACES.forEach(p -> out.add(KnowledgeKey.star(s, p)));
    }
    return List.copyOf(out);
  }

  /**
   * Number-check reading keys (xem số spec §6): intro 3, mệnh 5, ngũ hành relation 5, quẻ 64, âm
   * dương 3, cung phi 3, nút 3, one per folk pair, band 4, closing = 103.
   */
  public static List<KnowledgeKey> number() {
    List<KnowledgeKey> out = new ArrayList<>();
    for (NumberKind k : NumberKind.values()) {
      out.add(KnowledgeKey.number("intro", k.key()));
    }
    for (Element e : Element.values()) {
      out.add(KnowledgeKey.number("menh", e.key()));
    }
    for (String r : List.of("sinh_nhap", "tuong_hop", "khac_xuat", "sinh_xuat", "khac_nhap")) {
      out.add(KnowledgeKey.number("element", r));
    }
    for (int i = 1; i <= 64; i++) {
      out.add(KnowledgeKey.number("que", Integer.toString(i)));
    }
    for (String a : List.of("can_bang", "hoi_lech", "lech")) {
      out.add(KnowledgeKey.number("amduong", a));
    }
    for (String c : List.of("hop_ca_hai", "hop_mot", "chua_hop")) {
      out.add(KnowledgeKey.number("cungphi", c));
    }
    for (String n : List.of("cao", "vua", "thap")) {
      out.add(KnowledgeKey.number("nut", n));
    }
    FolkPairs.all().forEach(p -> out.add(KnowledgeKey.number("pair", p)));
    for (Band b : Band.values()) {
      out.add(KnowledgeKey.number("band", b.key()));
    }
    out.add(KnowledgeKey.numberClosing());
    return List.copyOf(out);
  }

  /** Star x brightness keys for one palace: 14 x 5 = 70. */
  public static List<KnowledgeKey> p2(String palace) {
    List<KnowledgeKey> out = new ArrayList<>();
    for (String s : MAJOR_STARS) {
      BRIGHTNESS.forEach(b -> out.add(KnowledgeKey.star(s, palace, b)));
    }
    return List.copyOf(out);
  }
}
