package io.github.mat973252.agentplatform.durable;

import io.github.mat973252.agentplatform.core.ApprovalCommand;
import io.github.mat973252.agentplatform.core.ApprovalDecision;
import io.github.mat973252.agentplatform.core.RunRequest;
import io.github.mat973252.agentplatform.core.RunSnapshot;
import io.github.mat973252.agentplatform.core.RunState;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;

public class DiagnosticsWorkflowImpl implements DiagnosticsWorkflow {
  private final DiagnosticsActivities activities = Workflow.newActivityStub(
      DiagnosticsActivities.class,
      ActivityOptions.newBuilder()
          .setStartToCloseTimeout(Duration.ofSeconds(10))
          .setScheduleToCloseTimeout(Duration.ofSeconds(45))
          .setRetryOptions(RetryOptions.newBuilder()
              .setInitialInterval(Duration.ofSeconds(1))
              .setMaximumInterval(Duration.ofSeconds(5))
              .setMaximumAttempts(3)
              .build())
          .build());

  private RunState state = RunState.CREATED;
  private RunRequest request;
  private String runId;
  private String approvalId;
  private String operationId;
  private String evidence;
  private String output;
  private ApprovalDecision decision;

  @Override
  public RunSnapshot execute(RunRequest input) {
    request = input;
    runId = Workflow.getInfo().getWorkflowId();
    approvalId = runId + ":approval:restart";
    operationId = runId + ":restart:1";
    try {
      return executeSteps();
    } catch (CanceledFailure failure) {
      state = RunState.CANCELLED;
      throw failure;
    } catch (ActivityFailure failure) {
      state = failure.getCause() instanceof CanceledFailure ? RunState.CANCELLED : RunState.FAILED;
      throw failure;
    }
  }

  private RunSnapshot executeSteps() {
    state = RunState.RUNNING;
    evidence = activities.readEvidence(request.service());
    state = RunState.WAITING_APPROVAL;
    boolean received = Workflow.await(
        Duration.ofSeconds(request.approvalTimeoutSeconds()), () -> decision != null);
    if (!received) {
      state = RunState.TIMED_OUT;
    } else if (decision == ApprovalDecision.REJECT) {
      state = RunState.REJECTED;
    } else {
      state = RunState.RUNNING;
      output = activities.executeAction(operationId, request.service());
      state = RunState.SUCCEEDED;
    }
    return snapshot();
  }

  @Override
  public void submitApproval(ApprovalCommand command) {
    if (state == RunState.WAITING_APPROVAL && decision == null
        && approvalId.equals(command.approvalId())) {
      decision = command.decision();
    }
  }

  @Override
  public RunSnapshot snapshot() {
    return new RunSnapshot(runId, state, request == null ? null : request.service(),
        approvalId, operationId, evidence, output);
  }
}
