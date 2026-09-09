package io.github.mat973252.agentplatform.api;

import io.temporal.api.history.v1.HistoryEvent;
import io.github.mat973252.agentplatform.core.RunState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

record RunHistoryProjection(RunObservation.Run run, List<RunObservation.Step> steps,
    List<RunObservation.Event> events) {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  static RunHistoryProjection map(String runId, String executionId, List<HistoryEvent> history, long observedAt) {
    if (history.isEmpty() || !history.getFirst().hasWorkflowExecutionStartedEventAttributes()
        || !history.getFirst().getWorkflowExecutionStartedEventAttributes().getWorkflowType().getName().equals("DiagnosticsWorkflow")) {
      throw new IllegalStateException("A complete DiagnosticsWorkflow history is required");
    }
    var steps = new LinkedHashMap<Long, RunObservation.Step>();
    var events = new ArrayList<RunObservation.Event>();
    String status = "RUNNING";
    String businessState = null;
    String reasonCode = null;
    Long closedAt = null;
    long expected = 1;
    for (var event : history) {
      if (event.getEventId() != expected++) throw new IllegalStateException("History contains an event gap");
      String type = event.getEventType().name().replace("EVENT_TYPE_", "");
      Long scheduled = scheduledId(event);
      Integer attempt = event.hasActivityTaskStartedEventAttributes()
          ? event.getActivityTaskStartedEventAttributes().getAttempt() : null;
      String activityId = null;
      if (event.hasActivityTaskScheduledEventAttributes()) {
        var attributes = event.getActivityTaskScheduledEventAttributes();
        scheduled = event.getEventId();
        activityId = attributes.getActivityId();
        steps.put(scheduled, new RunObservation.Step(activityId, attributes.getActivityType().getName(),
            decisionId(runId, event), scheduled, "SCHEDULED", time(event), null, null, null));
      } else if (scheduled != null) {
        var prior = steps.get(scheduled);
        if (prior == null) throw new IllegalStateException("Activity schedule is missing from history");
        activityId = prior.activityId();
        boolean started = event.hasActivityTaskStartedEventAttributes();
        steps.put(scheduled, new RunObservation.Step(activityId, prior.activityType(), prior.decisionId(), scheduled,
            type.replace("ACTIVITY_TASK_", ""), prior.scheduledAt(), started ? time(event) : prior.startedAt(),
            started ? null : time(event), started ? attempt : prior.lastRecordedAttempt()));
      }
      if (type.startsWith("WORKFLOW_EXECUTION_") && List.of("COMPLETED", "FAILED", "TIMED_OUT", "CANCELED",
          "TERMINATED", "CONTINUED_AS_NEW").contains(type.substring("WORKFLOW_EXECUTION_".length()))) {
        status = type.substring("WORKFLOW_EXECUTION_".length());
        closedAt = time(event);
      }
      if (event.hasWorkflowExecutionCompletedEventAttributes()) {
        var result = event.getWorkflowExecutionCompletedEventAttributes().getResult();
        if (result.getPayloadsCount() > 0) {
          var json = json(result.getPayloads(0));
          if (json != null) {
            String state = json.path("state").asText("");
            if (java.util.Arrays.stream(RunState.values()).anyMatch(value -> value.name().equals(state))) businessState = state;
            String reason = json.path("reasonCode").asText("");
            if (reason.matches("[A-Z][A-Z0-9_]{0,99}")) reasonCode = reason;
          }
        }
      }
      events.add(new RunObservation.Event(executionId + ":" + event.getEventId(), event.getEventId(), time(event),
          type, activityId, scheduled, attempt));
    }
    return new RunHistoryProjection(new RunObservation.Run(runId, executionId, status, businessState, reasonCode, time(history.getFirst()),
        closedAt, history.getLast().getEventId(), observedAt), List.copyOf(steps.values()), List.copyOf(events));
  }

  private static Long scheduledId(HistoryEvent event) {
    if (event.hasActivityTaskStartedEventAttributes()) return event.getActivityTaskStartedEventAttributes().getScheduledEventId();
    if (event.hasActivityTaskCompletedEventAttributes()) return event.getActivityTaskCompletedEventAttributes().getScheduledEventId();
    if (event.hasActivityTaskFailedEventAttributes()) return event.getActivityTaskFailedEventAttributes().getScheduledEventId();
    if (event.hasActivityTaskTimedOutEventAttributes()) return event.getActivityTaskTimedOutEventAttributes().getScheduledEventId();
    if (event.hasActivityTaskCanceledEventAttributes()) return event.getActivityTaskCanceledEventAttributes().getScheduledEventId();
    return null;
  }

  private static String decisionId(String runId, HistoryEvent event) {
    var attributes = event.getActivityTaskScheduledEventAttributes();
    if (!List.of("Plan", "PlanWithinBudget").contains(attributes.getActivityType().getName())
        || attributes.getInput().getPayloadsCount() == 0) return null;
    var payload = attributes.getInput().getPayloads(0);
    var json = json(payload);
    if (json == null) return null;
    var id = json.path("decisionId").asText("");
    String prefix = runId + ":model:";
    return id.startsWith(prefix) && id.substring(prefix.length()).matches("[1-9][0-9]*") ? id : null;
  }

  private static tools.jackson.databind.JsonNode json(io.temporal.api.common.v1.Payload payload) {
    if (!payload.getMetadataOrDefault("encoding", com.google.protobuf.ByteString.EMPTY).toStringUtf8().equals("json/plain")) return null;
    try { return JSON.readTree(payload.getData().toByteArray()); }
    catch (tools.jackson.core.JacksonException invalid) { throw new IllegalStateException("Unsupported history payload format"); }
  }

  static long time(HistoryEvent event) {
    return event.getEventTime().getSeconds() * 1000 + event.getEventTime().getNanos() / 1_000_000;
  }
}
