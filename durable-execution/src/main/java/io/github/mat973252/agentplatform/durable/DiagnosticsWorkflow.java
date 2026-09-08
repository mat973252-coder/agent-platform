package io.github.mat973252.agentplatform.durable;

import io.github.mat973252.agentplatform.core.ApprovalCommand;
import io.github.mat973252.agentplatform.core.RunRequest;
import io.github.mat973252.agentplatform.core.RunSnapshot;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface DiagnosticsWorkflow {
  @WorkflowMethod
  RunSnapshot execute(RunRequest request);

  @SignalMethod
  void submitApproval(ApprovalCommand command);

  @SignalMethod
  void requestReconciliation();

  @QueryMethod
  RunSnapshot snapshot();
}
