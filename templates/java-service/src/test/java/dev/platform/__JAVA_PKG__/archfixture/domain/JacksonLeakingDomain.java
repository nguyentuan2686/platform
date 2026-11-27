package dev.platform.__JAVA_PKG__.archfixture.domain;

import tools.jackson.databind.json.JsonMapper;

/** Deliberately violates the domain purity rule; used only by {@code ArchitectureRulesTest}. */
public class JacksonLeakingDomain {

  public String toJson() {
    return JsonMapper.shared().writeValueAsString(this);
  }
}
