package io.github.mat973252.agentplatform.permit;

import io.github.mat973252.agentplatform.core.ActionResult;
import io.github.mat973252.agentplatform.core.ActionStatus;

public record OperationRecord(String operationId, String approvalId, String fingerprint, String status,
    String reasonCode, String output, long startedAt, Long finishedAt, String closedBy,
    String closedReason, long reconciliationVersion, long deliveredVersion) {
  ActionResult result() {
    var action = switch (status) {
      case "SUCCEEDED" -> ActionStatus.EXECUTED;
      case "CLOSED_UNKNOWN" -> ActionStatus.CLOSED_UNKNOWN;
      case "IN_PROGRESS", "UNKNOWN" -> ActionStatus.RECONCILIATION_REQUIRED;
      default -> throw new IllegalStateException("Unknown execution state");
    };
    return new ActionResult(action, reasonCode, output);
  }
}
