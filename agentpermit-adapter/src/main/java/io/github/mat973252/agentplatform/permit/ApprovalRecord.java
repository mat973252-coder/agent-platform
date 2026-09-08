package io.github.mat973252.agentplatform.permit;

public record ApprovalRecord(String approvalId, String runId, String operationId, String stepId,
    String toolName, String resourceId, String fingerprint, long expiresAt, String status,
    String decidedBy, Long decidedAt, String cancelledBy, Long cancelledAt, boolean executionStarted) {}
