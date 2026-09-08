package io.github.mat973252.agentplatform.permit;

/** Explicit local acceptance-test faults, configured at process startup rather than by API callers. */
public enum DemoFailureMode {
  NONE, FAIL_BEFORE_LEDGER_WRITE, FAIL_AFTER_LEDGER_COMMIT, PAUSE_AFTER_LEDGER_COMMIT
}
