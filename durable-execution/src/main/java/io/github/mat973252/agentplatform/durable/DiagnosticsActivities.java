package io.github.mat973252.agentplatform.durable;

import io.github.mat973252.agentplatform.core.ActionResult;
import io.github.mat973252.agentplatform.core.ApprovalState;
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

  @ActivityMethod
  ActionResult prepareAction(String runId, String operationId, String service, String approvalId, long expiresAt);

  @ActivityMethod
  ApprovalState readApproval(String approvalId);

  @ActivityMethod
  ActionResult executeApprovedAction(String operationId, String service, String approvalId);
}
