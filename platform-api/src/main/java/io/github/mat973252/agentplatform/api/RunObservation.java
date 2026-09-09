package io.github.mat973252.agentplatform.api;

import java.util.List;

final class RunObservation {
  private RunObservation() {}

  record Run(String runId, String executionId, String executionStatus, String businessState, String reasonCode, long startedAt,
      Long closedAt, long lastEventId, long projectedAt) {}
  record Event(String id, long eventId, long occurredAt, String type, String activityId,
      Long scheduledEventId, Integer attempt) {}
  record Step(String activityId, String activityType, String decisionId, long scheduledEventId,
      String state, long scheduledAt, Long startedAt, Long closedAt, Integer lastRecordedAttempt) {}
  record ModelAttempt(String decisionId, int attempt, String status, String meteringMode, long reservedAt, Long settledAt,
      Long usedTokens, Long usedCostMicrousd) {}
  record Detail(Run run, List<Step> steps, List<ModelAttempt> modelAttempts, String attemptCoverage) {}
  record EventPage(List<Event> events, String nextCursor, long highWatermark, boolean hasMore, boolean closed) {}
  record Summary(String runId, String executionId, String executionStatus, long startedAt, Long closedAt) {}
  record Listing(List<Summary> runs, String nextPageToken) {}
}
