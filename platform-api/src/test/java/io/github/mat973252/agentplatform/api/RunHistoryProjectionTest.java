package io.github.mat973252.agentplatform.api;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.Timestamp;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunHistoryProjectionTest {
  static List<HistoryEvent> history() {
    return List.of(
        event(1, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED).setWorkflowExecutionStartedEventAttributes(
            WorkflowExecutionStartedEventAttributes.newBuilder().setWorkflowType(WorkflowType.newBuilder().setName("DiagnosticsWorkflow"))).build(),
        event(2, EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED).setActivityTaskScheduledEventAttributes(
            ActivityTaskScheduledEventAttributes.newBuilder().setActivityId("activity-1")
                .setActivityType(ActivityType.newBuilder().setName("ReadEvidence"))).build(),
        event(3, EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED).setActivityTaskStartedEventAttributes(
            ActivityTaskStartedEventAttributes.newBuilder().setScheduledEventId(2).setAttempt(3).setIdentity("secret-worker-host")).build(),
        event(4, EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED).setActivityTaskCompletedEventAttributes(
            ActivityTaskCompletedEventAttributes.newBuilder().setScheduledEventId(2).setStartedEventId(3)).build(),
        event(5, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED).setWorkflowExecutionCompletedEventAttributes(
            WorkflowExecutionCompletedEventAttributes.newBuilder()).build());
  }

  static HistoryEvent.Builder event(long id, EventType type) {
    return HistoryEvent.newBuilder().setEventId(id).setEventType(type)
        .setEventTime(Timestamp.newBuilder().setSeconds(100 + id));
  }

  @Test
  void mapsActivityIdentityAndOnlyTheRecordedRetryAttempt() {
    var projection = RunHistoryProjection.map("run-test", "execution-1", history(), 1234);
    assertEquals("COMPLETED", projection.run().executionStatus());
    assertEquals(5, projection.run().lastEventId());
    assertEquals(1, projection.steps().size());
    var step = projection.steps().getFirst();
    assertEquals("activity-1", step.activityId());
    assertEquals("COMPLETED", step.state());
    assertEquals(3, step.lastRecordedAttempt());
    assertEquals(2, step.scheduledEventId());
    assertEquals("execution-1:3", projection.events().get(2).id());
    assertFalse(projection.toString().contains("secret-worker-host"));
  }

  @Test
  void anUnreportedActivityIsScheduledWithUnknownAttemptNotZero() {
    var projection = RunHistoryProjection.map("run-test", "execution-1", history().subList(0, 2), 1234);
    assertEquals("RUNNING", projection.run().executionStatus());
    assertNull(projection.steps().getFirst().lastRecordedAttempt());
    assertNull(projection.steps().getFirst().closedAt());
  }

  @Test
  void completedExecutionKeepsBusinessFailureWithoutCopyingEvidenceOrModelText() {
    var events = new java.util.ArrayList<>(history());
    var result = new io.github.mat973252.agentplatform.core.RunSnapshot("run-test",
        io.github.mat973252.agentplatform.core.RunState.FAILED, "orders", null, null,
        "secret-evidence", "secret-model-output", "MODEL_USAGE_UNKNOWN");
    events.set(4, event(5, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED).setWorkflowExecutionCompletedEventAttributes(
        WorkflowExecutionCompletedEventAttributes.newBuilder().setResult(
            io.temporal.common.converter.DefaultDataConverter.STANDARD_INSTANCE.toPayloads(result).orElseThrow())).build());
    var projection = RunHistoryProjection.map("run-test", "execution-1", events, 1234);
    assertEquals("COMPLETED", projection.run().executionStatus());
    assertEquals("FAILED", projection.run().businessState());
    assertEquals("MODEL_USAGE_UNKNOWN", projection.run().reasonCode());
    assertFalse(projection.toString().contains("secret-"));
  }

  @Test
  void rejectsPartialOrWrongWorkflowHistoriesInsteadOfAdvancingCursor() {
    assertThrows(IllegalStateException.class, () -> RunHistoryProjection.map("run-test", "execution-1", history().subList(1, 5), 1234));
    assertThrows(IllegalStateException.class, () -> RunHistoryProjection.map("run-test", "execution-1", List.of(history().getFirst(), history().get(3)), 1234));
  }
}
