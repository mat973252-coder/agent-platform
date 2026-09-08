package io.github.mat973252.agentplatform.api;

import io.github.mat973252.agentplatform.core.AgentDecision;
import java.util.LinkedHashMap;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class ModelDecisionCodec {
  private final JsonMapper mapper = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  AgentDecision decode(String json, String service) {
    try {
      if (json == null || json.length() > 8192) throw new IllegalArgumentException("Decision exceeds size limit");
      var node = mapper.readTree(json);
      if (node == null || !node.isObject() || !Set.copyOf(node.propertyNames()).equals(
          Set.of("schemaVersion", "action", "tool", "arguments", "message"))) {
        throw new IllegalArgumentException("Unexpected decision fields");
      }
      var args = node.get("arguments");
      if (!args.isObject()) throw new IllegalArgumentException("Arguments must be an object");
      var arguments = new LinkedHashMap<String, String>();
      for (var entry : args.properties()) arguments.put(entry.getKey(), string(entry.getValue()));
      var decision = new AgentDecision(string(node.get("schemaVersion")), string(node.get("action")),
          node.get("tool").isNull() ? null : string(node.get("tool")), arguments, string(node.get("message")));
      decision.validateFor(service);
      return decision;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Model output does not match the decision contract", invalid);
    }
  }

  private String string(JsonNode node) {
    if (!node.isString()) throw new IllegalArgumentException("A string is required");
    return node.stringValue();
  }
}
