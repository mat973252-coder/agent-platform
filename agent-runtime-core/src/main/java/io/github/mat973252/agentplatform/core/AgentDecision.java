package io.github.mat973252.agentplatform.core;

import java.util.Map;
import java.util.Set;

public record AgentDecision(String schemaVersion, String action, String tool,
    Map<String, String> arguments, String message) {
  public void validateFor(String service) {
    if (!"1".equals(schemaVersion) || message == null || message.isBlank() || message.length() > 2000
        || arguments == null) throw new IllegalArgumentException("Invalid decision envelope");
    if ("CALL_TOOL".equals(action)) {
      if (tool == null || !Set.of("evidence.read", "ops.restart", "ops.verify").contains(tool)
          || !Map.of("service", service).equals(arguments)) {
        throw new IllegalArgumentException("Tool or arguments are outside the run scope");
      }
    } else if (!("FINISH".equals(action) || "NEED_CONTEXT".equals(action))
        || tool != null || !arguments.isEmpty()) {
      throw new IllegalArgumentException("Invalid terminal decision");
    }
  }
}
