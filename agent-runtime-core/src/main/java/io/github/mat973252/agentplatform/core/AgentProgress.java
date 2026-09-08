package io.github.mat973252.agentplatform.core;

public record AgentProgress(int modelSteps, String modelVersion, String promptVersion,
    String toolVersion, String runbookVersion, AgentDecision lastDecision, String observation, String conclusion,
    BudgetContext budget) {
  public AgentProgress(int modelSteps, String modelVersion, String promptVersion, String toolVersion,
      String runbookVersion, AgentDecision lastDecision, String observation, String conclusion) {
    this(modelSteps, modelVersion, promptVersion, toolVersion, runbookVersion, lastDecision, observation, conclusion, null);
  }
}
