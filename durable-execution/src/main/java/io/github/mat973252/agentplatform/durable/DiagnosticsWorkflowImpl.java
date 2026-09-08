package io.github.mat973252.agentplatform.durable;

import io.github.mat973252.agentplatform.core.ActionResult;
import io.github.mat973252.agentplatform.core.ActionStatus;
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
  private String reasonCode;
  private ApprovalDecision decision;
  private boolean persistentApproval;

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
    int version = Workflow.getVersion("agentpermit-action-v1", Workflow.DEFAULT_VERSION, 1);
    if (version != Workflow.DEFAULT_VERSION) {
      persistentApproval = Workflow.getVersion("persistent-approval-v1", Workflow.DEFAULT_VERSION, 1)
          != Workflow.DEFAULT_VERSION;
      if (persistentApproval) return executeWithPersistentApproval();
      var action = activities.attemptAction(operationId, request.service(), approvalId, false);
      reasonCode = action.reasonCode();
      if (action.status() != ActionStatus.APPROVAL_REQUIRED) {
        return finishAction(action);
      }
    }
    state = RunState.WAITING_APPROVAL;
    boolean received = Workflow.await(
        Duration.ofSeconds(request.approvalTimeoutSeconds()), () -> decision != null);
    if (!received) {
      state = RunState.TIMED_OUT;
    } else if (decision == ApprovalDecision.REJECT) {
      state = RunState.REJECTED;
    } else {
      state = RunState.RUNNING;
      if (version == Workflow.DEFAULT_VERSION) {
        output = activities.executeAction(operationId, request.service());
        state = RunState.SUCCEEDED;
      } else {
        return finishAction(activities.attemptAction(operationId, request.service(), approvalId, true));
      }
    }
    return snapshot();
  }

  private RunSnapshot executeWithPersistentApproval() {
    long deadline = Workflow.currentTimeMillis() + request.approvalTimeoutSeconds() * 1000L;
    var prepared = activities.prepareAction(runId, operationId, request.service(), approvalId, deadline);
    reasonCode = prepared.reasonCode();
    if (prepared.status() != ActionStatus.APPROVAL_REQUIRED) return finishAction(prepared);
    state = RunState.WAITING_APPROVAL;
    while (Workflow.currentTimeMillis() < deadline) {
      boolean received = Workflow.await(Duration.ofMillis(deadline - Workflow.currentTimeMillis()), () -> decision != null);
      if (!received) break;
      // Signals wake the Workflow; only the independent approval record determines the decision.
      decision = null;
      switch (activities.readApproval(approvalId)) {
        case APPROVE -> {
          state = RunState.RUNNING;
          return finishAction(activities.executeApprovedAction(operationId, request.service(), approvalId));
        }
        case REJECT -> { state = RunState.REJECTED; return snapshot(); }
        case CANCELLED -> { state = RunState.CANCELLED; return snapshot(); }
        case EXPIRED -> { state = RunState.TIMED_OUT; return snapshot(); }
        case PENDING -> { /* Ignore untrusted or stale notifications without extending the deadline. */ }
      }
    }
    state = RunState.TIMED_OUT;
    return snapshot();
  }

  private RunSnapshot finishAction(ActionResult action) {
    reasonCode = action.reasonCode();
    output = action.output();
    state = switch (action.status()) {
      case EXECUTED -> RunState.SUCCEEDED;
      case DENIED -> RunState.DENIED;
      case FAILED, APPROVAL_REQUIRED -> RunState.FAILED;
    };
    return snapshot();
  }

  @Override
  public void submitApproval(ApprovalCommand command) {
    if ((state == RunState.WAITING_APPROVAL || (persistentApproval && !state.terminal())) && decision == null
        && approvalId.equals(command.approvalId())) {
      decision = command.decision();
    }
  }

  @Override
  public RunSnapshot snapshot() {
    return new RunSnapshot(runId, state, request == null ? null : request.service(),
        approvalId, operationId, evidence, output, reasonCode);
  }
}
