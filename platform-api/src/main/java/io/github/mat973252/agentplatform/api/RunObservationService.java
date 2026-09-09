package io.github.mat973252.agentplatform.api;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest;
import io.temporal.client.WorkflowClient;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class RunObservationService {
  private final WorkflowClient client;
  private final JdbcRunProjectionStore store;
  private final Clock clock;

  RunObservationService(WorkflowClient client, JdbcRunProjectionStore store, Clock clock) {
    this.client = client;
    this.store = store;
    this.clock = clock;
  }

  RunObservation.Listing list(int limit, String pageToken) {
    requireLimit(limit);
    if (pageToken.length() > 8192) throw badCursor();
    ByteString token;
    try { token = ByteString.copyFrom(Base64.getUrlDecoder().decode(pageToken)); }
    catch (IllegalArgumentException invalid) { throw badCursor(); }
    try {
      var response = client.getWorkflowServiceStubs().blockingStub().listWorkflowExecutions(
          ListWorkflowExecutionsRequest.newBuilder().setNamespace(client.getOptions().getNamespace())
              .setQuery("WorkflowType = 'DiagnosticsWorkflow'").setPageSize(limit).setNextPageToken(token).build());
      var runs = response.getExecutionsList().stream().map(info -> new RunObservation.Summary(
          info.getExecution().getWorkflowId(), info.getExecution().getRunId(),
          info.getStatus().name().replace("WORKFLOW_EXECUTION_STATUS_", ""),
          info.getStartTime().getSeconds() * 1000 + info.getStartTime().getNanos() / 1_000_000,
          info.hasCloseTime() ? info.getCloseTime().getSeconds() * 1000 + info.getCloseTime().getNanos() / 1_000_000 : null)).toList();
      return new RunObservation.Listing(runs, Base64.getUrlEncoder().withoutPadding().encodeToString(response.getNextPageToken().toByteArray()));
    } catch (StatusRuntimeException unavailable) { throw serviceError(unavailable); }
  }

  RunObservation.Detail detail(String runId, boolean refresh) {
    try {
      var run = refresh ? refresh(runId) : store.get(runId);
      if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run projection was not found");
      return new RunObservation.Detail(run, store.steps(runId), store.modelAttempts(runId),
          "TEMPORAL_RECORDED_ATTEMPTS_ONLY; MODEL_ATTEMPTS_FROM_BUDGET_LEDGER");
    } catch (DataAccessException | tools.jackson.core.JacksonException unavailable) { throw observationUnavailable(); }
  }

  RunObservation.EventPage events(String runId, String after, int limit) {
    requireLimit(limit);
    try {
      var run = refresh(runId);
      long cursor = cursor(run, after);
      var events = store.events(runId, cursor, limit).stream().takeWhile(event -> event.eventId() <= run.lastEventId()).toList();
      long last = events.isEmpty() ? cursor : events.getLast().eventId();
      return new RunObservation.EventPage(events, run.executionId() + ":" + last,
          run.lastEventId(), last < run.lastEventId(), run.closedAt() != null);
    } catch (DataAccessException | tools.jackson.core.JacksonException unavailable) { throw observationUnavailable(); }
  }

  private RunObservation.Run refresh(String runId) {
    var description = client.newUntypedWorkflowStub(runId, Optional.empty(), Optional.empty()).describe();
    if (!description.getWorkflowType().equals("DiagnosticsWorkflow")) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run was not found");
    }
    String executionId = description.getExecution().getRunId();
    try (var stream = client.streamHistory(runId, executionId)) {
      var history = stream.limit(10_001).toList();
      if (history.size() > 10_000) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "History exceeds the projection limit");
      store.save(RunHistoryProjection.map(runId, executionId, history, clock.millis()));
      return store.get(runId);
    } catch (StatusRuntimeException unavailable) { throw serviceError(unavailable); }
  }

  private static long cursor(RunObservation.Run run, String cursor) {
    if (cursor.isEmpty()) return 0;
    String prefix = run.executionId() + ":";
    if (!cursor.startsWith(prefix) || cursor.length() > 100) throw badCursor();
    try {
      long id = Long.parseLong(cursor.substring(prefix.length()));
      if (id < 0 || id > run.lastEventId()) throw badCursor();
      return id;
    } catch (NumberFormatException invalid) { throw badCursor(); }
  }

  private static void requireLimit(int limit) {
    if (limit < 1 || limit > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limit must be between 1 and 500");
  }

  private static ResponseStatusException badCursor() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor for this Run execution");
  }

  private static ResponseStatusException serviceError(StatusRuntimeException error) {
    if (error.getStatus().getCode() == Status.Code.INVALID_ARGUMENT) return badCursor();
    if (error.getStatus().getCode() == Status.Code.NOT_FOUND) return new ResponseStatusException(HttpStatus.NOT_FOUND, "Run was not found");
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Workflow history is unavailable");
  }

  private static ResponseStatusException observationUnavailable() {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Run observation storage is unavailable");
  }
}
