package io.github.mat973252.agentplatform.durable;

import io.github.mat973252.agentplatform.core.AgentContext;
import io.github.mat973252.agentplatform.core.AgentDecision;
import io.github.mat973252.agentplatform.core.VerificationResult;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface AgentActivities {
  String readRunbook(String service);
  AgentDecision plan(AgentContext context);
  VerificationResult verifyOperation(String operationId, String service, String approvalId, String expectedOutput);
}
