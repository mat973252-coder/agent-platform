package io.github.mat973252.agentplatform.core;

public record AgentContext(String decisionId, String service, String evidence, String runbook,
    String observation, boolean writeCompleted, boolean verified,
    String modelVersion, String promptVersion, String toolVersion, String runbookVersion) {
  public static final String MODEL_VERSION = "offline-diagnostics-v1";
  public static final String PROMPT_VERSION = "diagnostics-prompt-v1";
  public static final String TOOL_VERSION = "orders-tools-v1";
  public static final String RUNBOOK_VERSION = "orders-runbook-v1";
  public static final int MAX_MODEL_STEPS = 6;

  public AgentContext(String decisionId, String service, String evidence, String runbook,
      String observation, boolean writeCompleted, boolean verified) {
    this(decisionId, service, evidence, runbook, observation, writeCompleted, verified,
        MODEL_VERSION, PROMPT_VERSION, TOOL_VERSION, RUNBOOK_VERSION);
  }
}
