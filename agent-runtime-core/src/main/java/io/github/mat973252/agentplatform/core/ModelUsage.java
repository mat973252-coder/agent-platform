package io.github.mat973252.agentplatform.core;

public record ModelUsage(long inputTokens, long outputTokens, long costMicrousd) {
  public ModelUsage {
    if (inputTokens < 0 || outputTokens < 0 || costMicrousd < 0 || inputTokens > Long.MAX_VALUE - outputTokens) {
      throw new IllegalArgumentException("Usage must be nonnegative and its token total must fit in a long");
    }
  }

  public long tokens() { return inputTokens + outputTokens; }
}
