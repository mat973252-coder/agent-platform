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

  static class FakeOperations implements DiagnosticsActivities {
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

    @Override
    public String readEvidence(String service) {
      if (++readAttempts <= readFailures) {
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
      return attemptAction(operationId, service, approvalId, false);
    }

    @Override
    public ApprovalState readApproval(String approvalId) { return approvalState; }

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
