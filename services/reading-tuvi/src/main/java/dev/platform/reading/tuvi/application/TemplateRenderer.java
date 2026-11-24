package dev.platform.reading.tuvi.application;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fills the named placeholders written between curly braces. A placeholder without a value renders
 * as the empty string (WARN logs only its name, never a value), and any curly brace left over
 * (unbalanced or malformed) is dropped, so a rendered string never contains a raw brace.
 */
final class TemplateRenderer {

  private static final Logger LOG = LoggerFactory.getLogger(TemplateRenderer.class);
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_]+)\\}");

  private TemplateRenderer() {}

  static String render(String template, Map<String, String> vars) {
    Matcher m = PLACEHOLDER.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String name = m.group(1);
      String value = vars.get(name);
      if (value == null) {
        LOG.warn("Unknown placeholder {} in knowledge text; rendered empty", name);
        value = "";
      }
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    return out.toString().replace("{", "").replace("}", "");
  }
}
