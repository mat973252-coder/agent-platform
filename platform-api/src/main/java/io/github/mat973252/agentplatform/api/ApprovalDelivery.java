package io.github.mat973252.agentplatform.api;

import io.github.mat973252.agentplatform.core.ApprovalCommand;
import io.github.mat973252.agentplatform.core.ApprovalDecision;
import io.github.mat973252.agentplatform.durable.DiagnosticsWorkflow;
import io.github.mat973252.agentplatform.permit.JdbcApprovalStore;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowStub;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "platform.approval-delivery.enabled", havingValue = "true", matchIfMissing = true)
class ApprovalDelivery {
  private static final System.Logger LOG = System.getLogger(ApprovalDelivery.class.getName());
  private final JdbcApprovalStore store;
  private final WorkflowClient client;

  ApprovalDelivery(JdbcApprovalStore store, WorkflowClient client) {
    this.store = store;
    this.client = client;
  }

  @Scheduled(fixedDelayString = "${platform.approval-delivery.interval-ms:1000}")
  void deliver() {
    for (var record : store.undelivered()) {
      try {
        var workflow = client.newWorkflowStub(DiagnosticsWorkflow.class, record.runId());
        if (!workflow.snapshot().state().terminal()) {
          if (record.status().equals("CANCELLED")) {
            WorkflowStub.fromTyped(workflow).cancel();
          } else {
            workflow.submitApproval(new ApprovalCommand(record.approvalId(), ApprovalDecision.valueOf(record.status())));
          }
        }
        store.delivered(record.approvalId(), record.status());
      } catch (WorkflowNotFoundException closed) {
        // Retention may have removed an old completed Run; never create a new execution for a delivery.
        store.delivered(record.approvalId(), record.status());
      } catch (WorkflowException unavailable) {
        LOG.log(System.Logger.Level.WARNING, "Approval delivery remains pending for Run {0}", record.runId());
      }
    }
  }
}
