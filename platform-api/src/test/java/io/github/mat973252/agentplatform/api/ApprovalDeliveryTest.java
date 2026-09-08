package io.github.mat973252.agentplatform.api;

import static org.mockito.Mockito.*;

import io.github.mat973252.agentplatform.core.ApprovalCommand;
import io.github.mat973252.agentplatform.core.RunSnapshot;
import io.github.mat973252.agentplatform.core.RunState;
import io.github.mat973252.agentplatform.durable.DiagnosticsWorkflow;
import io.github.mat973252.agentplatform.permit.ApprovalRecord;
import io.github.mat973252.agentplatform.permit.JdbcApprovalStore;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowServiceException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApprovalDeliveryTest {
  @Test
  void failedSignalKeepsTheDecisionPendingForTheNextDelivery() {
    var store = mock(JdbcApprovalStore.class);
    var client = mock(WorkflowClient.class);
    var workflow = mock(DiagnosticsWorkflow.class);
    var record = new ApprovalRecord("approval-1", "run-1", "run-1:restart:1", "restart:1",
        "ops.restart", "orders", "fingerprint", Long.MAX_VALUE, "APPROVE", "approver", 1L, null, null, false);
    when(store.undelivered()).thenReturn(List.of(record));
    when(client.newWorkflowStub(DiagnosticsWorkflow.class, "run-1")).thenReturn(workflow);
    when(workflow.snapshot()).thenReturn(new RunSnapshot("run-1", RunState.WAITING_APPROVAL,
        "orders", "approval-1", "run-1:restart:1", "synthetic", null, null));
    doThrow(mock(WorkflowServiceException.class)).doNothing().when(workflow).submitApproval(any(ApprovalCommand.class));
    var delivery = new ApprovalDelivery(store, client);

    delivery.deliver();
    verify(store, never()).delivered(anyString(), anyString());
    delivery.deliver();
    verify(store).delivered("approval-1", "APPROVE");
    verify(workflow, times(2)).submitApproval(any(ApprovalCommand.class));
  }
}
