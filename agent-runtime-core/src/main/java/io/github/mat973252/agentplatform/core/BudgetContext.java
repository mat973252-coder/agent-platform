package io.github.mat973252.agentplatform.core;

public record BudgetContext(String runId, RunBudget limits, long deadlineEpochMillis, ModelProfile model) {
  public BudgetContext(String runId, RunBudget limits, long deadlineEpochMillis) {
    this(runId, limits, deadlineEpochMillis, ModelProfile.offline());
  }
  public BudgetContext {
    if (model == null) model = ModelProfile.offline();
    if (runId == null || runId.isBlank() || limits == null || deadlineEpochMillis <= 0) {
      throw new IllegalArgumentException("A Run, limits and fixed deadline are required");
    }
  }
}
