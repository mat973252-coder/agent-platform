package io.github.mat973252.agentplatform.durable;

import static org.junit.jupiter.api.Assertions.*;

import io.github.mat973252.agentplatform.core.*;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class DiagnosticsWorkflowTest {
  private TestWorkflowEnvironment environment;
  private DiagnosticsWorkflow workflow;
  private FakeOperations operations;
  private String runId;

  @BeforeEach
  void setUp() {
    environment = TestWorkflowEnvironment.newInstance();
    var worker = environment.newWorker("test-diagnostics");
    worker.registerWorkflowImplementationTypes(DiagnosticsWorkflowImpl.class);
    operations = new FakeOperations();
    worker.registerActivitiesImplementations(operations);
    environment.start();
    runId = "run-" + UUID.randomUUID();
    workflow = environment.getWorkflowClient().newWorkflowStub(
        DiagnosticsWorkflow.class,
        WorkflowOptions.newBuilder().setWorkflowId(runId).setTaskQueue("test-diagnostics").build());
  }

  @AfterEach
  void tearDown() {
    environment.close();
  }

  @Test
  void approvalExecutesThePreparedOperationAndHistoryReplays() throws Exception {
    decideAfter(Duration.ofSeconds(1), ApprovalDecision.APPROVE);
    var result = workflow.execute(new RunRequest("orders", 30));
    assertEquals(RunState.SUCCEEDED, result.state());
    assertEquals(List.of(runId + ":restart:1"), operations.operationIds);
    WorkflowReplayer.replayWorkflowExecution(
        environment.getWorkflowClient().fetchHistory(runId), DiagnosticsWorkflowImpl.class);
    assertEquals(1, operations.operationIds.size(), "Replay must not execute the tool");
  }

  @Test
  void aWorkflowSignalAloneCannotAuthorizeTheTool() {
    environment.registerDelayedCallback(Duration.ofSeconds(1), () ->
        workflow.submitApproval(new ApprovalCommand(runId + ":approval:restart", ApprovalDecision.APPROVE)));
    assertEquals(RunState.TIMED_OUT, workflow.execute(new RunRequest("orders", 30)).state());
    assertTrue(operations.operationIds.isEmpty());
  }

  @Test
  void rejectionNeverExecutesTheTool() {
    decideAfter(Duration.ofSeconds(1), ApprovalDecision.REJECT);
    assertEquals(RunState.REJECTED, workflow.execute(new RunRequest("orders", 30)).state());
    assertTrue(operations.operationIds.isEmpty());
  }

  @Test
  void approvalTimeoutNeverExecutesTheTool() {
    assertEquals(RunState.TIMED_OUT, workflow.execute(new RunRequest("orders", 30)).state());
    assertTrue(operations.operationIds.isEmpty());
  }

  @Test
  void aDecisionForAnotherApprovalCannotResumeTheRun() {
    environment.registerDelayedCallback(Duration.ofSeconds(1), () ->
        workflow.submitApproval(new ApprovalCommand("wrong-id", ApprovalDecision.APPROVE)));
    assertEquals(RunState.TIMED_OUT, workflow.execute(new RunRequest("orders", 30)).state());
    assertTrue(operations.operationIds.isEmpty());
  }

  @Test
  void theFirstDecisionWins() {
    environment.registerDelayedCallback(Duration.ofSeconds(1), () -> {
      operations.approvalState = ApprovalState.REJECT;
      workflow.submitApproval(new ApprovalCommand(runId + ":approval:restart", ApprovalDecision.REJECT));
      workflow.submitApproval(new ApprovalCommand(runId + ":approval:restart", ApprovalDecision.APPROVE));
    });
    assertEquals(RunState.REJECTED, workflow.execute(new RunRequest("orders", 30)).state());
    assertTrue(operations.operationIds.isEmpty());
  }

  @Test
  void transientReadFailuresRetryBeforeApproval() {
    operations.readFailures = 2;
    decideAfter(Duration.ofSeconds(10), ApprovalDecision.APPROVE);
    assertEquals(RunState.SUCCEEDED, workflow.execute(new RunRequest("orders", 30)).state());
    assertEquals(3, operations.readAttempts);
  }

  @Test
  void retriedToolAttemptsKeepTheLogicalOperationId() {
    operations.toolFailures = 1;
    decideAfter(Duration.ofSeconds(1), ApprovalDecision.APPROVE);
    assertEquals(RunState.SUCCEEDED, workflow.execute(new RunRequest("orders", 30)).state());
    assertEquals(List.of(runId + ":restart:1", runId + ":restart:1"), operations.operationIds);
  }

  @Test
  void exhaustedReadRetriesFailWithoutExecutingTheTool() {
    operations.readFailures = 10;
    assertThrows(WorkflowFailedException.class, () -> workflow.execute(new RunRequest("orders", 30)));
    assertEquals(3, operations.readAttempts);
    assertEquals(RunState.FAILED, workflow.snapshot().state());
    assertTrue(operations.operationIds.isEmpty());
  }

  @Test
  void cancellationWhileWaitingNeverExecutesTheTool() {
    environment.registerDelayedCallback(Duration.ofSeconds(1), () -> WorkflowStub.fromTyped(workflow).cancel());
    assertThrows(WorkflowFailedException.class, () -> workflow.execute(new RunRequest("orders", 30)));
    assertEquals(RunState.CANCELLED, workflow.snapshot().state());
    assertTrue(operations.operationIds.isEmpty());
  }

  @Test
  void allowedActionCompletesWithoutWaitingForApproval() {
    operations.initialStatus = ActionStatus.EXECUTED;
    assertEquals(RunState.SUCCEEDED, workflow.execute(new RunRequest("orders", 30)).state());
    assertEquals(List.of(runId + ":restart:1"), operations.operationIds);
  }

  @Test
  void policyDenialEndsWithoutWaitingOrExecuting() {
    operations.initialStatus = ActionStatus.DENIED;
    var result = workflow.execute(new RunRequest("orders", 30));
    assertEquals(RunState.DENIED, result.state());
    assertEquals("SYNTHETIC_DENIED", result.reasonCode());
    assertTrue(operations.operationIds.isEmpty());
  }

  @Test
  void pipelineFailureProducesAnExplicitTerminalReason() {
    operations.initialStatus = ActionStatus.FAILED;
    var result = workflow.execute(new RunRequest("orders", 30));
    assertEquals(RunState.FAILED, result.state());
    assertEquals("SYNTHETIC_FAILED", result.reasonCode());
    assertNull(result.output());
    assertTrue(operations.operationIds.isEmpty());
  }

  @Test
  void authorizationIsRecheckedAfterTheWorkflowApproval() {
    operations.approvedStatus = ActionStatus.DENIED;
    decideAfter(Duration.ofSeconds(1), ApprovalDecision.APPROVE);
    assertEquals(RunState.DENIED, workflow.execute(new RunRequest("orders", 30)).state());
    assertTrue(operations.operationIds.isEmpty());
  }

  @Test
  void unusableApprovalFailsWithoutAnAutomaticApprovalLoop() {
    operations.approvedStatus = ActionStatus.APPROVAL_REQUIRED;
    decideAfter(Duration.ofSeconds(1), ApprovalDecision.APPROVE);
    var result = workflow.execute(new RunRequest("orders", 30));
    assertEquals(RunState.FAILED, result.state());
    assertEquals("SYNTHETIC_APPROVAL_REQUIRED", result.reasonCode());
    assertTrue(operations.operationIds.isEmpty());
  }

  private void decideAfter(Duration delay, ApprovalDecision decision) {
    environment.registerDelayedCallback(delay, () -> {
      assertEquals(RunState.WAITING_APPROVAL, workflow.snapshot().state());
      assertTrue(operations.operationIds.isEmpty());
      operations.approvalState = ApprovalState.valueOf(decision.name());
      workflow.submitApproval(new ApprovalCommand(runId + ":approval:restart", decision));
    });
  }

  @Test
  void anUnknownExecutionIsConfirmedOnlyByTheReconciliationActivity() {
    operations.approvedStatus = ActionStatus.RECONCILIATION_REQUIRED;
    operations.reconciled = new ActionResult(ActionStatus.EXECUTED, "LEDGER_CONFIRMED", "existing receipt");
    decideAfter(Duration.ofSeconds(1), ApprovalDecision.APPROVE);
    var result = workflow.execute(new RunRequest("orders", 30));
    assertEquals(RunState.SUCCEEDED, result.state());
    assertEquals("existing receipt", result.output());
    assertEquals(1, operations.reconciliationReads);
    assertTrue(operations.operationIds.isEmpty());
  }

  @Test
  void aReconciliationSignalCannotInventAResultOrCloseAnUnknownRun() {
    operations.approvedStatus = ActionStatus.RECONCILIATION_REQUIRED;
    decideAfter(Duration.ofSeconds(1), ApprovalDecision.APPROVE);
    environment.registerDelayedCallback(Duration.ofSeconds(2), () -> {
      assertEquals(RunState.RECONCILIATION_REQUIRED, workflow.snapshot().state());
      assertEquals(1, operations.decisionIds.size(), "An unknown write must block further planning");
      workflow.requestReconciliation();
    });
    environment.registerDelayedCallback(Duration.ofSeconds(3), () -> {
      assertEquals(RunState.RECONCILIATION_REQUIRED, workflow.snapshot().state());
      operations.reconciled = new ActionResult(ActionStatus.CLOSED_UNKNOWN, "OPERATOR_CLOSED_UNKNOWN", null);
      workflow.requestReconciliation();
    });
    assertEquals(RunState.CLOSED_UNKNOWN, workflow.execute(new RunRequest("orders", 30)).state());
    assertTrue(operations.operationIds.isEmpty());
  }

  @Test
  void exhaustedExecutionAttemptsPreserveTheUnknownOutcomeForManualResolution() {
    operations.durableFailures = 10;
    decideAfter(Duration.ofSeconds(1), ApprovalDecision.APPROVE);
    environment.registerDelayedCallback(Duration.ofSeconds(10), () -> {
      assertEquals(RunState.RECONCILIATION_REQUIRED, workflow.snapshot().state());
      operations.reconciled = new ActionResult(ActionStatus.CLOSED_UNKNOWN, "OPERATOR_CLOSED_UNKNOWN", null);
      workflow.requestReconciliation();
    });
    assertEquals(RunState.CLOSED_UNKNOWN, workflow.execute(new RunRequest("orders", 30)).state());
    assertEquals(3, operations.durableAttempts);
  }

  @Test
  void modelDecisionsAreRecordedAndReplayedWithoutCallingTheModelAgain() throws Exception {
    decideAfter(Duration.ofSeconds(1), ApprovalDecision.APPROVE);
    var result = workflow.execute(new RunRequest("orders", 30));
    assertNotNull(result.agent());
    assertEquals(3, result.agent().modelSteps());
    assertEquals("offline-diagnostics-v1", result.agent().modelVersion());
    assertEquals(List.of(runId + ":model:1", runId + ":model:2", runId + ":model:3"), operations.decisionIds);
    assertEquals(1, operations.verificationReads);
    WorkflowReplayer.replayWorkflowExecution(environment.getWorkflowClient().fetchHistory(runId), DiagnosticsWorkflowImpl.class);
    assertEquals(3, operations.decisionIds.size());
    assertEquals(1, operations.operationIds.size());
  }

  @Test
  void invalidModelToolCannotReachApprovalOrExecution() {
    operations.planScript.add(tool("shell.exec"));
    var result = workflow.execute(new RunRequest("orders", 30));
    assertEquals(RunState.FAILED, result.state());
    assertEquals("MODEL_OUTPUT_INVALID", result.reasonCode());
    assertEquals(0, operations.prepareCalls);
    assertTrue(operations.operationIds.isEmpty());
  }

  @Test
  void failedReadCanBeReplannedWithoutRetryingAWrite() {
    operations.planScript.addAll(List.of(tool("evidence.read"), tool("evidence.read"), finish()));
    operations.additionalReadFailures = 3;
    var result = workflow.execute(new RunRequest("orders", 30));
    assertEquals(RunState.SUCCEEDED, result.state());
    assertEquals(5, operations.readAttempts);
    assertEquals("TOOL_READ_FAILED", operations.contexts.get(1).observation());
    assertTrue(operations.operationIds.isEmpty());
  }

  @Test
  void modelCannotRepeatAConfirmedWriteOrFinishWithoutVerification() {
    operations.planScript.addAll(List.of(tool("ops.restart"), tool("ops.restart")));
    decideAfter(Duration.ofSeconds(1), ApprovalDecision.APPROVE);
    var result = workflow.execute(new RunRequest("orders", 30));
    assertEquals("MODEL_ACTION_INVALID", result.reasonCode());
    assertEquals(RunState.FAILED, result.state());
    assertEquals(1, operations.operationIds.size());
  }

  @Test
  void aModelSuccessClaimCannotReplaceVerification() {
    operations.planScript.addAll(List.of(tool("ops.restart"), finish()));
    decideAfter(Duration.ofSeconds(1), ApprovalDecision.APPROVE);
    var result = workflow.execute(new RunRequest("orders", 30));
    assertEquals(RunState.FAILED, result.state());
    assertEquals("VERIFICATION_REQUIRED", result.reasonCode());
    assertEquals(1, operations.operationIds.size());
  }

  @Test
  void unconfirmedVerificationAllowsReadOnlyReplanning() {
    operations.planScript.addAll(List.of(tool("ops.restart"), tool("ops.verify"), finish()));
    operations.verificationConfirmed = false;
    decideAfter(Duration.ofSeconds(1), ApprovalDecision.APPROVE);
    var result = workflow.execute(new RunRequest("orders", 30));
    assertEquals(RunState.FAILED, result.state());
    assertEquals("VERIFICATION_REQUIRED", result.reasonCode());
    assertEquals("VERIFICATION_NOT_CONFIRMED", operations.contexts.get(2).observation());
    assertEquals(1, operations.operationIds.size());
  }

  @Test
  void aLoopStopsAtTheServerDefinedStepLimit() {
    for (int i = 0; i < AgentContext.MAX_MODEL_STEPS; i++) operations.planScript.add(tool("evidence.read"));
    var result = workflow.execute(new RunRequest("orders", 30));
    assertEquals(RunState.FAILED, result.state());
    assertEquals("STEP_LIMIT_EXCEEDED", result.reasonCode());
    assertEquals(AgentContext.MAX_MODEL_STEPS, operations.decisionIds.size());
    assertTrue(operations.operationIds.isEmpty());
  }

  @Test
  void insufficientContextHasAnExplicitTerminalReason() {
    operations.planScript.add(new AgentDecision("1", "NEED_CONTEXT", null, Map.of(), "Need more evidence"));
    var result = workflow.execute(new RunRequest("orders", 30));
    assertEquals(RunState.FAILED, result.state());
    assertEquals("CONTEXT_INSUFFICIENT", result.reasonCode());
    assertTrue(operations.operationIds.isEmpty());
  }

  private static AgentDecision tool(String name) {
    return new AgentDecision("1", "CALL_TOOL", name, Map.of("service", "orders"), "Synthetic plan");
  }

  @Test
  void transientModelRetriesUseTheSameDecisionId() {
    operations.modelFailures = 2;
    operations.planScript.add(finish());
    assertEquals(RunState.SUCCEEDED, workflow.execute(new RunRequest("orders", 30)).state());
    assertEquals(List.of(runId + ":model:1", runId + ":model:1", runId + ":model:1"), operations.decisionIds);
    assertTrue(operations.operationIds.isEmpty());
  }

  @Test
  void exhaustedModelRetriesFailWithoutToolExecution() {
    operations.modelFailures = 10;
    var result = workflow.execute(new RunRequest("orders", 30));
    assertEquals(RunState.FAILED, result.state());
    assertEquals("MODEL_FAILED", result.reasonCode());
    assertEquals(3, operations.decisionIds.size());
    assertEquals(0, operations.prepareCalls);
  }

  @Test
  void invalidParsedModelOutputIsNotRetriedOrReplanned() {
    operations.invalidModelOutput = true;
    var result = workflow.execute(new RunRequest("orders", 30));
    assertEquals("MODEL_OUTPUT_INVALID", result.reasonCode());
    assertEquals(1, operations.decisionIds.size());
    assertTrue(operations.operationIds.isEmpty());
  }

  @Test
  void aVerificationReadFailureCanBeReplannedWithoutAnotherWrite() {
    operations.verificationFailures = 3;
    operations.planScript.addAll(List.of(tool("ops.restart"), tool("ops.verify"), tool("ops.verify"), finish()));
    decideAfter(Duration.ofSeconds(1), ApprovalDecision.APPROVE);
    var result = workflow.execute(new RunRequest("orders", 30));
    assertEquals(RunState.SUCCEEDED, result.state());
    assertEquals("VERIFICATION_READ_FAILED", operations.contexts.get(2).observation());
    assertEquals(4, operations.verificationReads);
    assertEquals(1, operations.operationIds.size());
  }

  private static AgentDecision finish() {
    return new AgentDecision("1", "FINISH", null, Map.of(), "Synthetic diagnostic conclusion");
  }

  @Test
  void totalTimeBudgetIncludesApprovalWaitingAndLimitsTheApprovalDeadline() {
    var result = workflow.execute(budgetRequest(2));
    assertEquals(RunState.TIMED_OUT, result.state());
    assertEquals("RUN_TIME_BUDGET_EXCEEDED", result.reasonCode());
    assertNotNull(operations.budgetContext);
    assertEquals(operations.budgetContext.deadlineEpochMillis(), operations.preparedDeadline);
    assertTrue(operations.operationIds.isEmpty());
  }

  @Test
  void modelRetryBackoffCannotExtendTheTotalTimeBudget() {
    operations.modelFailures = 10;
    var result = workflow.execute(budgetRequest(2));
    assertEquals(RunState.TIMED_OUT, result.state());
    assertEquals("RUN_TIME_BUDGET_EXCEEDED", result.reasonCode());
    assertTrue(operations.decisionIds.size() <= 2);
    assertTrue(operations.operationIds.isEmpty());
  }

  @Test
  void approvalReadRetriesCannotStartAWriteAfterTheDeadline() {
    operations.approvalReadFailures = 10;
    decideAfter(Duration.ofSeconds(1), ApprovalDecision.APPROVE);
    var result = workflow.execute(budgetRequest(2));
    assertEquals(RunState.TIMED_OUT, result.state());
    assertEquals("RUN_TIME_BUDGET_EXCEEDED", result.reasonCode());
    assertTrue(operations.operationIds.isEmpty());
  }

  @Test
  void anUnknownWriteRemainsResolvableAfterTheTimeBudgetExpires() {
    operations.approvedStatus = ActionStatus.RECONCILIATION_REQUIRED;
    decideAfter(Duration.ofSeconds(1), ApprovalDecision.APPROVE);
    environment.registerDelayedCallback(Duration.ofSeconds(3), () -> {
      assertEquals(RunState.RECONCILIATION_REQUIRED, workflow.snapshot().state());
      assertEquals(1, operations.decisionIds.size());
      operations.reconciled = new ActionResult(ActionStatus.CLOSED_UNKNOWN, "OPERATOR_CLOSED_UNKNOWN", null);
      workflow.requestReconciliation();
    });
    assertEquals(RunState.CLOSED_UNKNOWN, workflow.execute(budgetRequest(2)).state());
    assertTrue(operations.operationIds.isEmpty());
  }

  @Test
  void lateConfirmationDoesNotRestartPlanningAfterTheBudgetExpires() {
    operations.approvedStatus = ActionStatus.RECONCILIATION_REQUIRED;
    decideAfter(Duration.ofSeconds(1), ApprovalDecision.APPROVE);
    environment.registerDelayedCallback(Duration.ofSeconds(3), () -> {
      operations.reconciled = new ActionResult(ActionStatus.EXECUTED, "LEDGER_CONFIRMED", "existing receipt");
      workflow.requestReconciliation();
    });
    var result = workflow.execute(budgetRequest(2));
    assertEquals(RunState.TIMED_OUT, result.state());
    assertEquals("existing receipt", result.output());
    assertEquals(1, operations.decisionIds.size());
    assertEquals(0, operations.verificationReads);
  }

  @Test
  void theRunPinsItsOwnModelStepLimit() {
    operations.planScript.addAll(List.of(tool("evidence.read"), finish()));
    var result = workflow.execute(new RunRequest("orders", 30, new RunBudget(1, 30, 10000, 10000)));
    assertEquals("STEP_LIMIT_EXCEEDED", result.reasonCode());
    assertEquals(1, operations.decisionIds.size());
  }

  @Test
  void exhaustedTokensHaveASeparateReasonAndCannotReachTheTool() {
    operations.budgetFailureReason = "TOKEN_BUDGET_EXCEEDED";
    operations.planScript.add(finish());
    var result = workflow.execute(budgetRequest(30));
    assertEquals(RunState.FAILED, result.state());
    assertEquals("TOKEN_BUDGET_EXCEEDED", result.reasonCode());
    assertTrue(operations.operationIds.isEmpty());
  }

  private RunRequest budgetRequest(int seconds) {
    return new RunRequest("orders", 30, new RunBudget(6, seconds, 100000, 1000000));
  }

  static class FakeOperations implements DiagnosticsActivities, AgentActivities, BudgetActivities {
    int readFailures;
    int readAttempts;
    int toolFailures;
    ActionStatus initialStatus = ActionStatus.APPROVAL_REQUIRED;
    ActionStatus approvedStatus = ActionStatus.EXECUTED;
    ApprovalState approvalState = ApprovalState.PENDING;
    int durableFailures;
    int durableAttempts;
    int reconciliationReads;
    ActionResult reconciled = new ActionResult(ActionStatus.RECONCILIATION_REQUIRED, "OUTCOME_UNKNOWN", null);
    final List<String> operationIds = new ArrayList<>();
    final List<String> decisionIds = new ArrayList<>();
    final List<AgentContext> contexts = new ArrayList<>();
    final List<AgentDecision> planScript = new ArrayList<>();
    int additionalReadFailures;
    int prepareCalls;
    int verificationReads;
    boolean verificationConfirmed = true;
    int modelFailures;
    boolean invalidModelOutput;
    int verificationFailures;
    int approvalReadFailures;
    int approvalReads;
    BudgetContext budgetContext;
    long preparedDeadline;
    String budgetFailureReason;

    @Override
    public void initializeBudget(BudgetContext context) { budgetContext = context; }

    @Override
    public AgentDecision planWithinBudget(AgentContext context, BudgetContext budget) {
      if (budgetFailureReason != null) throw ApplicationFailure.newNonRetryableFailure("Budget rejected", budgetFailureReason);
      return plan(context);
    }

    @Override
    public String readRunbook(String service) { return "Synthetic fixed orders runbook"; }

    @Override
    public AgentDecision plan(AgentContext context) {
      decisionIds.add(context.decisionId());
      if (invalidModelOutput) throw ApplicationFailure.newNonRetryableFailure("Bad fixture", "MODEL_OUTPUT_INVALID");
      if (decisionIds.size() <= modelFailures) throw ApplicationFailure.newFailure("Model unavailable", "MODEL_UNAVAILABLE");
      contexts.add(context);
      if (!planScript.isEmpty()) return planScript.get(contexts.size() - 1);
      if (context.verified()) return finish();
      return tool(context.writeCompleted() ? "ops.verify" : "ops.restart");
    }

    @Override
    public VerificationResult verifyOperation(String operationId, String service, String approvalId, String expectedOutput) {
      verificationReads++;
      if (verificationReads <= verificationFailures) throw ApplicationFailure.newFailure("Read unavailable", "READ_UNAVAILABLE");
      return new VerificationResult(verificationConfirmed, "Synthetic ledger verification");
    }

    @Override
    public String readEvidence(String service) {
      if (++readAttempts <= readFailures || (readAttempts > 1 && readAttempts <= additionalReadFailures + 1)) {
        throw ApplicationFailure.newFailure("Synthetic read failure", "DEMO_READ_FAILURE");
      }
      return "Synthetic 5xx evidence for " + service;
    }

    @Override
    public String executeAction(String operationId, String service) {
      operationIds.add(operationId);
      if (operationIds.size() <= toolFailures) {
        throw ApplicationFailure.newFailure("Synthetic failure before side effect", "DEMO_TOOL_FAILURE");
      }
      return "SIMULATED_RESTART:" + service;
    }

    @Override
    public ActionResult attemptAction(String operationId, String service, String approvalId, boolean approved) {
      var status = approved ? approvedStatus : initialStatus;
      return new ActionResult(status, "SYNTHETIC_" + status,
          status == ActionStatus.EXECUTED ? executeAction(operationId, service) : null);
    }

    @Override
    public ActionResult prepareAction(String runId, String operationId, String service, String approvalId, long expiresAt) {
      prepareCalls++;
      preparedDeadline = expiresAt;
      return attemptAction(operationId, service, approvalId, false);
    }

    @Override
    public ApprovalState readApproval(String approvalId) {
      if (++approvalReads <= approvalReadFailures) throw ApplicationFailure.newFailure("Read unavailable", "READ_UNAVAILABLE");
      return approvalState;
    }

    @Override
    public ActionResult executeApprovedAction(String operationId, String service, String approvalId) {
      return attemptAction(operationId, service, approvalId, true);
    }

    @Override
    public ActionResult executeDurableAction(String operationId, String service, String approvalId) {
      if (++durableAttempts <= durableFailures) throw ApplicationFailure.newFailure("Synthetic lost response", "LOST_RESPONSE");
      return attemptAction(operationId, service, approvalId, true);
    }

    @Override
    public ActionResult reconcileAction(String operationId, String service, String approvalId) {
      reconciliationReads++;
      return reconciled;
    }
  }
}
