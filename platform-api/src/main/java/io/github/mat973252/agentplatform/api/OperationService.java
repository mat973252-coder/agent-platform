package io.github.mat973252.agentplatform.api;

import io.github.mat973252.agentplatform.core.RunState;
import io.github.mat973252.agentplatform.permit.*;
import org.springframework.stereotype.Service;

@Service
class OperationService {
  private final RunService runs;
  private final JdbcApprovalStore approvals;
  private final JdbcExecutionStore executions;
  private final JdbcRestartLedger ledger;
  private final DurableGovernance governance;

  OperationService(RunService runs, JdbcApprovalStore approvals, JdbcExecutionStore executions,
      JdbcRestartLedger ledger, DurableGovernance governance) {
    this.runs = runs;
    this.approvals = approvals;
    this.executions = executions;
    this.ledger = ledger;
    this.governance = governance;
  }

  OperationView view(String runId) {
    runs.snapshot(runId);
    var approval = approval(runId);
    return new OperationView(executions.find(approval.operationId()), ledger.find(approval.operationId()), ledger.generation(approval.resourceId()));
  }

  void requestCheck(String runId) {
    var record = pending(runId);
    executions.requestCheck(record.operationId());
  }

  void closeUnknown(String runId, String actor, String reason) {
    var approval = approval(runId);
    var record = executions.find(approval.operationId());
    if (record == null || !record.status().equals("CLOSED_UNKNOWN")) record = pending(runId);
    executions.closeUnknown(record.operationId(), actor, reason);
  }

  private OperationRecord pending(String runId) {
    if (runs.snapshot(runId).state() != RunState.RECONCILIATION_REQUIRED) {
      throw new IllegalStateException("Run is not awaiting outcome reconciliation");
    }
    var approval = approval(runId);
    return governance.fenceUnknown(approval.operationId(), approval.resourceId(), approval.approvalId());
  }

  private ApprovalRecord approval(String runId) {
    var approval = approvals.findByRun(runId);
    if (approval == null) throw new IllegalStateException("Run has no persistent operation binding");
    return approval;
  }

  record OperationView(OperationRecord execution, RestartReceipt receipt, long serviceGeneration) {}
}
