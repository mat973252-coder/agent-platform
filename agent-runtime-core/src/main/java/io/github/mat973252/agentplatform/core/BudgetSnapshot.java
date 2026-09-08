package io.github.mat973252.agentplatform.core;

public record BudgetSnapshot(String runId, RunBudget limits, long deadlineEpochMillis,
    long usedTokens, long reservedTokens, long usedCostMicrousd, long reservedCostMicrousd,
    long modelAttempts, String meteringMode) {}
