package io.github.mat973252.agentplatform.durable;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface DiagnosticsActivities {
  @ActivityMethod
  String readEvidence(String service);

  @ActivityMethod
  String executeAction(String operationId, String service);
}
