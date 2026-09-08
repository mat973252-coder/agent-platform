package io.github.mat973252.agentplatform.core;

public enum RunState {
  CREATED,
  RUNNING,
  WAITING_APPROVAL,
  RECONCILIATION_REQUIRED,
  CLOSED_UNKNOWN,
  SUCCEEDED,
  DENIED,
  REJECTED,
  TIMED_OUT,
  CANCELLED,
  FAILED;

  public boolean terminal() {
    return this != CREATED && this != RUNNING && this != WAITING_APPROVAL && this != RECONCILIATION_REQUIRED;
  }
}
