package dev.platform.reading.tuvi.adapter.out.knowledge;

/** A knowledge file is invalid; the message names {@code <file>:<line>}. */
public class KnowledgeImportException extends RuntimeException {

  public KnowledgeImportException(String message) {
    super(message);
  }

  public KnowledgeImportException(String message, Throwable cause) {
    super(message, cause);
  }
}
