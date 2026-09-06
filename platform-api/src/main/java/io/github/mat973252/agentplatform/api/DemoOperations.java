package io.github.mat973252.agentplatform.api;

import io.github.mat973252.agentplatform.durable.DiagnosticsActivities;
import io.github.mat973252.agentplatform.core.ActionResult;
import io.github.mat973252.agentplatform.core.ActionStatus;
import io.github.mat973252.agentplatform.permit.LocalDemoGovernance;
import io.temporal.failure.ApplicationFailure;
import org.springframework.stereotype.Component;

@Component
class DemoOperations implements DiagnosticsActivities {
  private final LocalDemoGovernance governance = new LocalDemoGovernance();

  @Override
  public String readEvidence(String service) {
    return "SYNTHETIC: orders 5xx rate=12%; fixed demo recommends a simulated restart.";
  }

  @Override
  public String executeAction(String operationId, String service) {
    // Compatibility Activity for P0 histories, scheduled only after their approval signal.
    var result = attemptAction(operationId, service, operationId + ":legacy-approval", true);
    if (result.status() != ActionStatus.EXECUTED) {
      throw ApplicationFailure.newNonRetryableFailure(result.reasonCode(), "GOVERNANCE_" + result.status());
    }
    return result.output();
  }

  @Override
  public ActionResult attemptAction(String operationId, String service, String approvalId, boolean approved) {
    return governance.restart(operationId, service, approvalId, approved);
  }
}
