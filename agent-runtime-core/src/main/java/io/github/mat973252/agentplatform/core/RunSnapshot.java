package io.github.mat973252.agentplatform.core;

public record RunSnapshot(
    String runId,
    RunState state,
    String service,
    String approvalId,
    String operationId,
    String evidence,
    String output,
    String reasonCode,
    AgentProgress agent) {
  public RunSnapshot(String runId, RunState state, String service, String approvalId, String operationId,
      String evidence, String output, String reasonCode) {
    this(runId, state, service, approvalId, operationId, evidence, output, reasonCode, null);
  }
}
