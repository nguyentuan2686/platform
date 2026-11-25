package dev.platform.reading.tuvi.domain.qa;

/** Q&A topic (ASCII ids are the identity contract). */
public enum QaTopic {
  TinhDuyen("tình duyên"),
  SuNghiep("sự nghiệp"),
  TaiLoc("tài lộc"),
  SucKhoe("sức khỏe"),
  GiaDao("gia đạo"),
  VanNam("vận năm");

  private final String labelVi;

  QaTopic(String labelVi) {
    this.labelVi = labelVi;
  }

  public String labelVi() {
    return labelVi;
  }
}
