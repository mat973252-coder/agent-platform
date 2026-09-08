package io.github.mat973252.agentplatform.durable;

import io.github.mat973252.agentplatform.core.ActionResult;
import io.github.mat973252.agentplatform.core.ActionStatus;
import io.github.mat973252.agentplatform.core.AgentContext;
import io.github.mat973252.agentplatform.core.AgentDecision;
import io.github.mat973252.agentplatform.core.AgentProgress;
import io.github.mat973252.agentplatform.core.ApprovalCommand;
import io.github.mat973252.agentplatform.core.ApprovalDecision;
import io.github.mat973252.agentplatform.core.RunRequest;
import io.github.mat973252.agentplatform.core.RunSnapshot;
import io.github.mat973252.agentplatform.core.RunState;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
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
  private final AgentActivities agentActivities = Workflow.newActivityStub(AgentActivities.class,
      ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10))
          .setScheduleToCloseTimeout(Duration.ofSeconds(45))
          .setRetryOptions(RetryOptions.newBuilder().setInitialInterval(Duration.ofSeconds(1))
              .setMaximumInterval(Duration.ofSeconds(5)).setMaximumAttempts(3).build()).build());

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
  private boolean reconciliationRequested;
  private boolean agentLoop;
  private int modelSteps;
  private AgentDecision lastDecision;
  private String observation;
  private String conclusion;
  private boolean writeCompleted;
  private boolean verified;

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
    if (Workflow.getVersion("agent-loop-v1", Workflow.DEFAULT_VERSION, 1) != Workflow.DEFAULT_VERSION) {
      return executeAgentLoop();
    }
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

  private RunSnapshot executeAgentLoop() {
    agentLoop = true;
    persistentApproval = true;
    String runbook;
    try { runbook = agentActivities.readRunbook(request.service()); }
    catch (ActivityFailure failure) { return agentFailure(failure, "CONTEXT_UNAVAILABLE"); }
    for (int step = 1; step <= AgentContext.MAX_MODEL_STEPS; step++) {
      modelSteps = step;
      try {
        lastDecision = agentActivities.plan(new AgentContext(runId + ":model:" + step, request.service(),
            evidence, runbook, observation, writeCompleted, verified));
      } catch (ActivityFailure failure) { return agentFailure(failure, "MODEL_FAILED"); }
      try {
        if (lastDecision == null) throw new IllegalArgumentException("Missing model decision");
        lastDecision.validateFor(request.service());
      } catch (IllegalArgumentException invalid) { return failAgent("MODEL_OUTPUT_INVALID"); }
      if (lastDecision.action().equals("NEED_CONTEXT")) {
        conclusion = lastDecision.message();
        return failAgent("CONTEXT_INSUFFICIENT");
      }
      if (lastDecision.action().equals("FINISH")) {
        if (writeCompleted && !verified) return failAgent("VERIFICATION_REQUIRED");
        conclusion = lastDecision.message();
        state = RunState.SUCCEEDED;
        if (!writeCompleted) reasonCode = "AGENT_COMPLETED";
        return snapshot();
      }
      runPlannedTool(lastDecision.tool());
      if (state.terminal()) return snapshot();
    }
    return failAgent("STEP_LIMIT_EXCEEDED");
  }

  private void runPlannedTool(String tool) {
    switch (tool) {
      case "evidence.read" -> {
        try {
          evidence = activities.readEvidence(request.service());
          observation = "EVIDENCE_READ_COMPLETED";
        } catch (ActivityFailure failure) {
          if (failure.getCause() instanceof CanceledFailure) throw failure;
          observation = "TOOL_READ_FAILED";
        }
      }
      case "ops.restart" -> {
        if (writeCompleted) { failAgent("MODEL_ACTION_INVALID"); return; }
        executeWithPersistentApproval();
        if (state != RunState.SUCCEEDED) return;
        writeCompleted = true;
        observation = "WRITE_CONFIRMED";
        state = RunState.RUNNING;
      }
      case "ops.verify" -> verifyPlannedOperation();
      default -> failAgent("MODEL_OUTPUT_INVALID");
    }
  }

  private void verifyPlannedOperation() {
    if (!writeCompleted) { failAgent("MODEL_ACTION_INVALID"); return; }
    try {
      var verification = agentActivities.verifyOperation(operationId, request.service(), approvalId, output);
      verified = verification != null && verification.confirmed();
      observation = verified ? "VERIFICATION_CONFIRMED: " + verification.detail() : "VERIFICATION_NOT_CONFIRMED";
    } catch (ActivityFailure failure) {
      if (failure.getCause() instanceof CanceledFailure) throw failure;
      verified = false;
      observation = "VERIFICATION_READ_FAILED";
    }
  }

  private RunSnapshot agentFailure(ActivityFailure failure, String fallback) {
    if (failure.getCause() instanceof CanceledFailure) throw failure;
    return failAgent(failure.getCause() instanceof ApplicationFailure application
        && application.getType().equals("MODEL_OUTPUT_INVALID") ? "MODEL_OUTPUT_INVALID" : fallback);
  }

  private RunSnapshot failAgent(String reason) {
    state = RunState.FAILED;
    reasonCode = reason;
    return snapshot();
  }

  private RunSnapshot executeWithPersistentApproval() {
    int resultVersion = Workflow.getVersion("durable-result-v1", Workflow.DEFAULT_VERSION, 1);
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
          if (resultVersion != Workflow.DEFAULT_VERSION) return executeWithDurableResult();
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

  private RunSnapshot executeWithDurableResult() {
    try {
      var result = activities.executeDurableAction(operationId, request.service(), approvalId);
      if (result.status() != ActionStatus.RECONCILIATION_REQUIRED) return finishAction(result);
      reasonCode = result.reasonCode();
    } catch (ActivityFailure failure) {
      if (failure.getCause() instanceof CanceledFailure) throw failure;
      reasonCode = "ACTIVITY_RESULT_UNCONFIRMED";
    }
    return awaitReconciliation();
  }

  private RunSnapshot awaitReconciliation() {
    state = RunState.RECONCILIATION_REQUIRED;
    while (true) {
      reconciliationRequested = false;
      try {
        var result = activities.reconcileAction(operationId, request.service(), approvalId);
        if (result.status() != ActionStatus.RECONCILIATION_REQUIRED) return finishAction(result);
        reasonCode = result.reasonCode();
      } catch (ActivityFailure failure) {
        if (failure.getCause() instanceof CanceledFailure) throw failure;
        reasonCode = "RECONCILIATION_UNAVAILABLE";
      }
      // A human may request another read or close the unknown task; neither action retries the tool.
      Workflow.await(() -> reconciliationRequested);
    }
  }

  private RunSnapshot finishAction(ActionResult action) {
    reasonCode = action.reasonCode();
    output = action.output();
    state = switch (action.status()) {
      case EXECUTED -> RunState.SUCCEEDED;
      case DENIED -> RunState.DENIED;
      case CLOSED_UNKNOWN -> RunState.CLOSED_UNKNOWN;
      case RECONCILIATION_REQUIRED -> RunState.RECONCILIATION_REQUIRED;
      case FAILED, APPROVAL_REQUIRED -> RunState.FAILED;
    };
    return snapshot();
  }

  @Override
  public void requestReconciliation() {
    if (!state.terminal()) reconciliationRequested = true;
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
        approvalId, operationId, evidence, output, reasonCode,
        agentLoop ? new AgentProgress(modelSteps, AgentContext.MODEL_VERSION, AgentContext.PROMPT_VERSION,
            AgentContext.TOOL_VERSION, AgentContext.RUNBOOK_VERSION, lastDecision, observation, conclusion) : null);
  }
}
