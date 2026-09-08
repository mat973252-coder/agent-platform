package io.github.mat973252.agentplatform.permit;

public record RestartReceipt(String operationId, String fingerprint, String service, long generation, long committedAt) {
  public String output() {
    return "TEST_LEDGER_RESTART:" + service + ";generation=" + generation + ";operationId=" + operationId;
  }
}
