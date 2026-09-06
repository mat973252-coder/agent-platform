package io.github.mat973252.agentplatform.core;

public record RunSnapshot(
    String runId,
    RunState state,
    String service,
    String approvalId,
    String operationId,
    String evidence,
    String output) {}
