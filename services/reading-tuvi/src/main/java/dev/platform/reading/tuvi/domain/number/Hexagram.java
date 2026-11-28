package dev.platform.reading.tuvi.domain.number;

import static dev.platform.reading.tuvi.domain.number.Hexagram.Fortune.BINH;
import static dev.platform.reading.tuvi.domain.number.Hexagram.Fortune.CAT;
import static dev.platform.reading.tuvi.domain.number.Hexagram.Fortune.HUNG;
import static dev.platform.reading.tuvi.domain.number.Trigram.CAN_SON;
import static dev.platform.reading.tuvi.domain.number.Trigram.CAN_THIEN;
import static dev.platform.reading.tuvi.domain.number.Trigram.CHAN;
import static dev.platform.reading.tuvi.domain.number.Trigram.DOAI;
import static dev.platform.reading.tuvi.domain.number.Trigram.KHAM;
import static dev.platform.reading.tuvi.domain.number.Trigram.KHON;
import static dev.platform.reading.tuvi.domain.number.Trigram.LY;
import static dev.platform.reading.tuvi.domain.number.Trigram.TON;

import java.util.List;

/**
 * One of the 64 hexagrams: King Wen number, upper and lower trigram, Vietnamese name and a folk
 * fortune class. The cát/bình/hung classes follow the split most Vietnamese number-reading sites
 * use (good: Càn, Khôn, Thái, Đại Hữu, Khiêm, Ích, Đỉnh...; bad: Truân, Tụng, Bĩ, Bác, Khảm, Minh
 * Di, Kiển, Khốn...); it is a builder's choice kept in this one table (spec §5.3, §10).
 */
public record Hexagram(int id, Trigram upper, Trigram lower, String name, Fortune fortune) {

  public enum Fortune {
    CAT,
    BINH,
    HUNG
  }

  private static final List<Hexagram> ALL =
      List.of(
          new Hexagram(1, CAN_THIEN, CAN_THIEN, "Thuần Càn", CAT),
          new Hexagram(2, KHON, KHON, "Thuần Khôn", CAT),
          new Hexagram(3, KHAM, CHAN, "Thủy Lôi Truân", HUNG),
          new Hexagram(4, CAN_SON, KHAM, "Sơn Thủy Mông", BINH),
          new Hexagram(5, KHAM, CAN_THIEN, "Thủy Thiên Nhu", BINH),
          new Hexagram(6, CAN_THIEN, KHAM, "Thiên Thủy Tụng", HUNG),
          new Hexagram(7, KHON, KHAM, "Địa Thủy Sư", BINH),
          new Hexagram(8, KHAM, KHON, "Thủy Địa Tỷ", CAT),
          new Hexagram(9, TON, CAN_THIEN, "Phong Thiên Tiểu Súc", BINH),
          new Hexagram(10, CAN_THIEN, DOAI, "Thiên Trạch Lý", BINH),
          new Hexagram(11, KHON, CAN_THIEN, "Địa Thiên Thái", CAT),
          new Hexagram(12, CAN_THIEN, KHON, "Thiên Địa Bĩ", HUNG),
          new Hexagram(13, CAN_THIEN, LY, "Thiên Hỏa Đồng Nhân", CAT),
          new Hexagram(14, LY, CAN_THIEN, "Hỏa Thiên Đại Hữu", CAT),
          new Hexagram(15, KHON, CAN_SON, "Địa Sơn Khiêm", CAT),
          new Hexagram(16, CHAN, KHON, "Lôi Địa Dự", CAT),
          new Hexagram(17, DOAI, CHAN, "Trạch Lôi Tùy", CAT),
          new Hexagram(18, CAN_SON, TON, "Sơn Phong Cổ", HUNG),
          new Hexagram(19, KHON, DOAI, "Địa Trạch Lâm", CAT),
          new Hexagram(20, TON, KHON, "Phong Địa Quán", BINH),
          new Hexagram(21, LY, CHAN, "Hỏa Lôi Phệ Hạp", BINH),
          new Hexagram(22, CAN_SON, LY, "Sơn Hỏa Bí", BINH),
          new Hexagram(23, CAN_SON, KHON, "Sơn Địa Bác", HUNG),
          new Hexagram(24, KHON, CHAN, "Địa Lôi Phục", CAT),
          new Hexagram(25, CAN_THIEN, CHAN, "Thiên Lôi Vô Vọng", BINH),
          new Hexagram(26, CAN_SON, CAN_THIEN, "Sơn Thiên Đại Súc", CAT),
          new Hexagram(27, CAN_SON, CHAN, "Sơn Lôi Di", BINH),
          new Hexagram(28, DOAI, TON, "Trạch Phong Đại Quá", HUNG),
          new Hexagram(29, KHAM, KHAM, "Thuần Khảm", HUNG),
          new Hexagram(30, LY, LY, "Thuần Ly", BINH),
          new Hexagram(31, DOAI, CAN_SON, "Trạch Sơn Hàm", CAT),
          new Hexagram(32, CHAN, TON, "Lôi Phong Hằng", CAT),
          new Hexagram(33, CAN_THIEN, CAN_SON, "Thiên Sơn Độn", HUNG),
          new Hexagram(34, CHAN, CAN_THIEN, "Lôi Thiên Đại Tráng", CAT),
          new Hexagram(35, LY, KHON, "Hỏa Địa Tấn", CAT),
          new Hexagram(36, KHON, LY, "Địa Hỏa Minh Di", HUNG),
          new Hexagram(37, TON, LY, "Phong Hỏa Gia Nhân", CAT),
          new Hexagram(38, LY, DOAI, "Hỏa Trạch Khuê", HUNG),
          new Hexagram(39, KHAM, CAN_SON, "Thủy Sơn Kiển", HUNG),
          new Hexagram(40, CHAN, KHAM, "Lôi Thủy Giải", CAT),
          new Hexagram(41, CAN_SON, DOAI, "Sơn Trạch Tổn", BINH),
          new Hexagram(42, TON, CHAN, "Phong Lôi Ích", CAT),
          new Hexagram(43, DOAI, CAN_THIEN, "Trạch Thiên Quải", BINH),
          new Hexagram(44, CAN_THIEN, TON, "Thiên Phong Cấu", HUNG),
          new Hexagram(45, DOAI, KHON, "Trạch Địa Tụy", CAT),
          new Hexagram(46, KHON, TON, "Địa Phong Thăng", CAT),
          new Hexagram(47, DOAI, KHAM, "Trạch Thủy Khốn", HUNG),
          new Hexagram(48, KHAM, TON, "Thủy Phong Tỉnh", CAT),
          new Hexagram(49, DOAI, LY, "Trạch Hỏa Cách", BINH),
          new Hexagram(50, LY, TON, "Hỏa Phong Đỉnh", CAT),
          new Hexagram(51, CHAN, CHAN, "Thuần Chấn", BINH),
          new Hexagram(52, CAN_SON, CAN_SON, "Thuần Cấn", BINH),
          new Hexagram(53, TON, CAN_SON, "Phong Sơn Tiệm", CAT),
          new Hexagram(54, CHAN, DOAI, "Lôi Trạch Quy Muội", HUNG),
          new Hexagram(55, CHAN, LY, "Lôi Hỏa Phong", CAT),
          new Hexagram(56, LY, CAN_SON, "Hỏa Sơn Lữ", BINH),
          new Hexagram(57, TON, TON, "Thuần Tốn", BINH),
          new Hexagram(58, DOAI, DOAI, "Thuần Đoài", CAT),
          new Hexagram(59, TON, KHAM, "Phong Thủy Hoán", BINH),
          new Hexagram(60, KHAM, DOAI, "Thủy Trạch Tiết", BINH),
          new Hexagram(61, TON, DOAI, "Phong Trạch Trung Phu", CAT),
          new Hexagram(62, CHAN, CAN_SON, "Lôi Sơn Tiểu Quá", HUNG),
          new Hexagram(63, KHAM, LY, "Thủy Hỏa Ký Tế", CAT),
          new Hexagram(64, LY, KHAM, "Hỏa Thủy Vị Tế", HUNG));

  public static List<Hexagram> all() {
    return ALL;
  }

  public static Hexagram of(Trigram upper, Trigram lower) {
    for (Hexagram h : ALL) {
      if (h.upper == upper && h.lower == lower) {
        return h;
      }
    }
    throw new IllegalStateException("hexagram table incomplete");
  }

  public static Hexagram byId(int id) {
    return ALL.get(id - 1);
  }
}
