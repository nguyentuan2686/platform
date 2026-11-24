package dev.platform.reading.tuvi.domain;

/** Life area shown in "Hôm nay", tied to one natal palace. */
public enum Area {
  CongViec("QuanLoc"),
  DiLai("ThienDi"),
  TienBac("TaiBach"),
  SucKhoe("TatAch");

  private final String palaceKey;

  Area(String palaceKey) {
    this.palaceKey = palaceKey;
  }

  public String palaceKey() {
    return palaceKey;
  }
}
