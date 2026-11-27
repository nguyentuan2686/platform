package dev.platform.__JAVA_PKG__;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** Loads the repository's contract (cwd is the module directory under Maven). */
public final class ContractSupport {

  static final Path CONTRACTS = Path.of("..", "..", "contracts");

  private ContractSupport() {}

  public static JsonNode openApi() {
    try {
      Path file = CONTRACTS.resolve("openapi").resolve("__SERVICE__.yaml");
      return YAMLMapper.builder().build().readTree(Files.readString(file));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The named OpenAPI component schema, with the components kept so that local $refs resolve. */
  public static Schema componentSchema(String name) {
    ObjectNode root = JsonMapper.shared().createObjectNode();
    root.put("$ref", "#/components/schemas/" + name);
    root.set("components", openApi().get("components"));
    return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        .getSchema(root.toString());
  }
}
