package io.github.mat973252.agentplatform.core;

public record RunRequest(String service, int approvalTimeoutSeconds, RunBudget budget) {
  public RunRequest(String service, int approvalTimeoutSeconds) {
    this(service, approvalTimeoutSeconds, RunBudget.defaults());
  }

  public RunRequest {
    if (budget == null) budget = RunBudget.defaults();
    if (!"orders".equals(service)) {
      throw new IllegalArgumentException("Only the fixed orders demo is supported");
    }
    if (approvalTimeoutSeconds < 1 || approvalTimeoutSeconds > 3600) {
      throw new IllegalArgumentException("Approval timeout must be between 1 and 3600 seconds");
    }
  }
}
