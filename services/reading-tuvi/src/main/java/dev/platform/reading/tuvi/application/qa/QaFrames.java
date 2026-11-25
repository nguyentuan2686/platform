package dev.platform.reading.tuvi.application.qa;

import dev.platform.reading.tuvi.application.KnowledgePort;
import dev.platform.reading.tuvi.domain.KnowledgeKey;
import dev.platform.reading.tuvi.domain.qa.RefusalReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The server-side Q&A frames ({@code tuvi:frame=qa_*}); the LLM never writes these texts. */
public class QaFrames {

  private static final Logger LOG = LoggerFactory.getLogger(QaFrames.class);
  static final String LANG = "vi";

  /** Used only if the disclaimer frame is missing, so an answer never goes out without one. */
  static final String FALLBACK_DISCLAIMER =
      "Lời ta nói là nội dung do AI biên soạn, mang tính tham khảo và giải trí thôi con nhé.";

  private final KnowledgePort knowledge;

  public QaFrames(KnowledgePort knowledge) {
    this.knowledge = knowledge;
  }

  public String disclaimer() {
    String text = frame("qa_disclaimer");
    return text.isEmpty() ? FALLBACK_DISCLAIMER : text;
  }

  public String forRefusal(RefusalReason reason) {
    return frame(
        switch (reason) {
          case MEDICAL -> "qa_refuse_medical";
          case LEGAL -> "qa_refuse_legal";
          case FINANCE -> "qa_refuse_finance";
          case GAMBLING -> "qa_refuse_gambling";
          case DEATH -> "qa_refuse_death";
          case SELF_HARM -> "qa_refuse_selfharm";
          case INSUFFICIENT -> "qa_insufficient";
          case INJECTION, OFF_TOPIC -> "qa_refuse_offtopic";
        });
  }

  public String queued() {
    return frame("qa_queued");
  }

  public String busy() {
    return frame("qa_busy");
  }

  public String refunded() {
    return frame("qa_refunded");
  }

  private String frame(String id) {
    return knowledge
        .find(KnowledgeKey.frame(id), LANG)
        .orElseGet(
            () -> {
              LOG.warn("qa frame {} missing from the knowledge base", id);
              return "";
            });
  }
}
