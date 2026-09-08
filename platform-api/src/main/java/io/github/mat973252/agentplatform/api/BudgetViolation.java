package io.github.mat973252.agentplatform.api;

final class BudgetViolation extends RuntimeException {
  private final String reason;
  BudgetViolation(String reason) { super(reason); this.reason = reason; }
  String reason() { return reason; }
}
