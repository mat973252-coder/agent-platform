package io.github.mat973252.agentplatform.core;

public record BudgetContext(String runId, RunBudget limits, long deadlineEpochMillis) {
  public BudgetContext {
    if (runId == null || runId.isBlank() || limits == null || deadlineEpochMillis <= 0) {
      throw new IllegalArgumentException("A Run, limits and fixed deadline are required");
    }
  }
}
