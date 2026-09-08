package io.github.mat973252.agentplatform.core;

public record RunBudget(int maxModelSteps, int maxDurationSeconds, long maxTokens, long maxCostMicrousd) {
  public RunBudget {
    if (maxModelSteps < 1 || maxModelSteps > 100 || maxDurationSeconds < 1 || maxDurationSeconds > 86400
        || maxTokens < 1 || maxTokens > 1_000_000_000L || maxCostMicrousd < 1 || maxCostMicrousd > 1_000_000_000L) {
      throw new IllegalArgumentException("Budget limits must be positive and within server bounds");
    }
  }

  public static RunBudget defaults() { return new RunBudget(6, 900, 100_000, 1_000_000); }
}
