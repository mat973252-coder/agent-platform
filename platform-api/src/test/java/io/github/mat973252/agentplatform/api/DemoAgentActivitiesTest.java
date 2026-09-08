package io.github.mat973252.agentplatform.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.mat973252.agentplatform.core.AgentContext;
import io.github.mat973252.agentplatform.permit.ApprovalRecord;
import io.github.mat973252.agentplatform.permit.JdbcApprovalStore;
import io.github.mat973252.agentplatform.permit.JdbcRestartLedger;
import io.github.mat973252.agentplatform.permit.RestartReceipt;
import org.junit.jupiter.api.Test;

class DemoAgentActivitiesTest {
  private final JdbcApprovalStore approvals = mock(JdbcApprovalStore.class);
  private final JdbcRestartLedger ledger = mock(JdbcRestartLedger.class);
  private final DemoAgentActivities activities = new DemoAgentActivities(approvals, ledger);

  @Test
  void packagedOfflineFixturesFollowTheFixedRunbook() {
    String runbook = activities.readRunbook("orders");
    assertTrue(runbook.contains("one logical restart"));
    assertEquals("ops.restart", activities.plan(context(runbook, false, false)).tool());
    assertEquals("ops.verify", activities.plan(context(runbook, true, false)).tool());
    assertEquals("FINISH", activities.plan(context(runbook, true, true)).action());
    verifyNoInteractions(ledger, approvals);
  }

  @Test
  void onlyTheBoundReceiptAndExpectedOutputCanConfirmAnOperation() {
    var approval = new ApprovalRecord("approval", "run", "operation", "restart:1", "ops.restart",
        "orders", "fingerprint", 1, "APPROVE", "approver", 1L, null, null, true);
    when(approvals.get("approval")).thenReturn(approval);
    assertFalse(activities.verifyOperation("operation", "orders", "approval", "invented output").confirmed());
    var receipt = new RestartReceipt("operation", "fingerprint", "orders", 1, 1);
    when(ledger.find("operation")).thenReturn(receipt);
    assertTrue(activities.verifyOperation("operation", "orders", "approval", receipt.output()).confirmed());
    assertFalse(activities.verifyOperation("operation", "orders", "approval", "invented output").confirmed());
    when(ledger.find("operation")).thenReturn(new RestartReceipt("operation", "different", "orders", 1, 1));
    assertFalse(activities.verifyOperation("operation", "orders", "approval", receipt.output()).confirmed());
    verify(ledger, never()).restart(anyString(), anyString(), anyString());
  }

  private AgentContext context(String runbook, boolean written, boolean verified) {
    return new AgentContext("run:model:1", "orders", "Synthetic evidence", runbook, null, written, verified);
  }

  @Test
  void aRequestedModelVersionCannotSilentlyUseAnotherFixture() {
    var context = new AgentContext("run:model:1", "orders", "evidence", "runbook", null, false, false,
        "different-model", AgentContext.PROMPT_VERSION, AgentContext.TOOL_VERSION, AgentContext.RUNBOOK_VERSION);
    assertThrows(io.temporal.failure.ApplicationFailure.class, () -> activities.plan(context));
  }
}
