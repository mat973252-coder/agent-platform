package io.github.mat973252.agentplatform.api;

import io.github.mat973252.agentplatform.durable.DiagnosticsWorkflow;
import io.github.mat973252.agentplatform.permit.JdbcApprovalStore;
import io.github.mat973252.agentplatform.permit.JdbcExecutionStore;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowNotFoundException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
class ReconciliationDelivery {
  private static final System.Logger LOG = System.getLogger(ReconciliationDelivery.class.getName());
  private final JdbcExecutionStore executions;
  private final JdbcApprovalStore approvals;
  private final WorkflowClient client;

  ReconciliationDelivery(JdbcExecutionStore executions, JdbcApprovalStore approvals, WorkflowClient client) {
    this.executions = executions;
    this.approvals = approvals;
    this.client = client;
  }

  @Scheduled(fixedDelayString = "${platform.reconciliation-delivery.interval-ms:1000}")
  void deliver() {
    for (var record : executions.undelivered()) {
      try {
        var runId = approvals.get(record.approvalId()).runId();
        var workflow = client.newWorkflowStub(DiagnosticsWorkflow.class, runId);
        if (!workflow.snapshot().state().terminal()) workflow.requestReconciliation();
        executions.delivered(record.operationId(), record.reconciliationVersion());
      } catch (WorkflowNotFoundException expired) {
        executions.delivered(record.operationId(), record.reconciliationVersion());
      } catch (WorkflowException unavailable) {
        LOG.log(System.Logger.Level.WARNING, "Reconciliation remains pending for operation {0}", record.operationId());
      }
    }
  }
}
