package dev.platform.reading.tuvi.application.qa;

/** No such reading for this subject (another subject's reading is reported the same way). */
public class QaNotFoundException extends RuntimeException {

  public QaNotFoundException() {
    super("Reading not found");
  }
}
