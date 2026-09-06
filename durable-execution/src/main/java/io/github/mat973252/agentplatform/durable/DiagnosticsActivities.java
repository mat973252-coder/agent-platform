package io.github.mat973252.agentplatform.durable;

import io.github.mat973252.agentplatform.core.ActionResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface DiagnosticsActivities {
  @ActivityMethod
  String readEvidence(String service);

  @ActivityMethod
  String executeAction(String operationId, String service);

  @ActivityMethod
  ActionResult attemptAction(String operationId, String service, String approvalId, boolean approved);
}
