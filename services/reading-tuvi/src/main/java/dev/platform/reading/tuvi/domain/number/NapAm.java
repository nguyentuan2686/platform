package dev.platform.reading.tuvi.domain.number;

import java.util.List;

/** Can chi of a lunar year on the 60-year Giáp Tý cycle and its nạp âm (spec §5.2). */
public final class NapAm {

  private static final List<String> CAN =
      List.of("Giáp", "Ất", "Bính", "Đinh", "Mậu", "Kỷ", "Canh", "Tân", "Nhâm", "Quý");

  private static final List<String> CHI =
      List.of("Tý", "Sửu", "Dần", "Mão", "Thìn", "Tỵ", "Ngọ", "Mùi", "Thân", "Dậu", "Tuất", "Hợi");

  private record Entry(String name, Element element) {}

  /** One entry per pair of years, starting at Giáp Tý / Ất Sửu. */
  private static final List<Entry> TABLE =
      List.of(
          new Entry("Hải Trung Kim", Element.KIM),
          new Entry("Lư Trung Hỏa", Element.HOA),
          new Entry("Đại Lâm Mộc", Element.MOC),
          new Entry("Lộ Bàng Thổ", Element.THO),
          new Entry("Kiếm Phong Kim", Element.KIM),
          new Entry("Sơn Đầu Hỏa", Element.HOA),
          new Entry("Giản Hạ Thủy", Element.THUY),
          new Entry("Thành Đầu Thổ", Element.THO),
          new Entry("Bạch Lạp Kim", Element.KIM),
          new Entry("Dương Liễu Mộc", Element.MOC),
          new Entry("Tuyền Trung Thủy", Element.THUY),
          new Entry("Ốc Thượng Thổ", Element.THO),
          new Entry("Tích Lịch Hỏa", Element.HOA),
          new Entry("Tùng Bách Mộc", Element.MOC),
          new Entry("Trường Lưu Thủy", Element.THUY),
          new Entry("Sa Trung Kim", Element.KIM),
          new Entry("Sơn Hạ Hỏa", Element.HOA),
          new Entry("Bình Địa Mộc", Element.MOC),
          new Entry("Bích Thượng Thổ", Element.THO),
          new Entry("Kim Bạch Kim", Element.KIM),
          new Entry("Phú Đăng Hỏa", Element.HOA),
          new Entry("Thiên Hà Thủy", Element.THUY),
          new Entry("Đại Trạch Thổ", Element.THO),
          new Entry("Thoa Xuyến Kim", Element.KIM),
          new Entry("Tang Đố Mộc", Element.MOC),
          new Entry("Đại Khê Thủy", Element.THUY),
          new Entry("Sa Trung Thổ", Element.THO),
          new Entry("Thiên Thượng Hỏa", Element.HOA),
          new Entry("Thạch Lựu Mộc", Element.MOC),
          new Entry("Đại Hải Thủy", Element.THUY));

  private NapAm() {}

  public static String canChi(int lunarYear) {
    return CAN.get(Math.floorMod(lunarYear - 4, 10))
        + " "
        + CHI.get(Math.floorMod(lunarYear - 4, 12));
  }

  public static String name(int lunarYear) {
    return entry(lunarYear).name();
  }

  public static Element element(int lunarYear) {
    return entry(lunarYear).element();
  }

  private static Entry entry(int lunarYear) {
    return TABLE.get(Math.floorMod(lunarYear - 4, 60) / 2);
  }
}
