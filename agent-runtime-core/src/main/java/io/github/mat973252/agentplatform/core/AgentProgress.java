package io.github.mat973252.agentplatform.core;

public record AgentProgress(int modelSteps, String modelVersion, String promptVersion,
    String toolVersion, String runbookVersion, AgentDecision lastDecision, String observation, String conclusion) {}
