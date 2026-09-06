package io.github.mat973252.agentplatform.core;

import java.util.Objects;

public record ApprovalCommand(String approvalId, ApprovalDecision decision) {
  public ApprovalCommand {
    if (approvalId == null || approvalId.isBlank()) {
      throw new IllegalArgumentException("Approval ID is required");
    }
    Objects.requireNonNull(decision, "decision");
  }
}
