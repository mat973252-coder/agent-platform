package io.github.mat973252.agentplatform.api;

import io.github.mat973252.agentplatform.durable.DiagnosticsActivities;
import org.springframework.stereotype.Component;

@Component
class DemoOperations implements DiagnosticsActivities {
  @Override
  public String readEvidence(String service) {
    return "SYNTHETIC: orders 5xx rate=12%; fixed demo recommends a simulated restart.";
  }

  @Override
  public String executeAction(String operationId, String service) {
    return "SIMULATED_RESTART:" + service + ";operationId=" + operationId;
  }
}
