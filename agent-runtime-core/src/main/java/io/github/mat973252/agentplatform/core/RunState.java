package io.github.mat973252.agentplatform.core;

public enum RunState {
  CREATED,
  RUNNING,
  WAITING_APPROVAL,
  SUCCEEDED,
  REJECTED,
  TIMED_OUT,
  CANCELLED,
  FAILED;

  public boolean terminal() {
    return this != CREATED && this != RUNNING && this != WAITING_APPROVAL;
  }
}
