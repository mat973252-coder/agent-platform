package io.github.mat973252.agentplatform.api;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

final class JdbcRunProjectionStore {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;

  JdbcRunProjectionStore(DataSource source) {
    jdbc = new JdbcTemplate(source);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
  }

  void save(RunHistoryProjection projection) {
    var run = projection.run();
    // The empty parent is discoverable only by this observation subsystem, never by execution.
    try {
      jdbc.update("INSERT INTO platform_run_views(run_id,execution_id,last_event_id,view_json) VALUES (?,?,0,?)",
          run.runId(), run.executionId(), JSON.writeValueAsString(run));
    } catch (DuplicateKeyException existing) { /* Serialize refreshes on the original binding below. */ }
    transaction.executeWithoutResult(status -> {
      var row = jdbc.queryForMap("SELECT execution_id,last_event_id FROM platform_run_views WHERE run_id=? FOR UPDATE", run.runId());
      if (!run.executionId().equals(row.get("execution_id"))) throw new IllegalStateException("Run execution binding changed; cursor cannot be reused");
      if (((Number) row.get("last_event_id")).longValue() > run.lastEventId()) return;
      // Replacing the bounded projection also repairs deleted/corrupt rows without changing event IDs.
      jdbc.update("DELETE FROM platform_run_events WHERE run_id=?", run.runId());
      jdbc.update("DELETE FROM platform_run_steps WHERE run_id=?", run.runId());
      jdbc.batchUpdate("INSERT INTO platform_run_events(run_id,event_id,event_json) VALUES (?,?,?)", projection.events(), 100,
          (ps, event) -> { ps.setString(1, run.runId()); ps.setLong(2, event.eventId()); ps.setString(3, JSON.writeValueAsString(event)); });
      jdbc.batchUpdate("INSERT INTO platform_run_steps(run_id,scheduled_event_id,step_json) VALUES (?,?,?)", projection.steps(), 100,
          (ps, step) -> { ps.setString(1, run.runId()); ps.setLong(2, step.scheduledEventId()); ps.setString(3, JSON.writeValueAsString(step)); });
      jdbc.update("UPDATE platform_run_views SET last_event_id=?,view_json=? WHERE run_id=?",
          run.lastEventId(), JSON.writeValueAsString(run), run.runId());
    });
  }

  RunObservation.Run get(String runId) {
    var rows = jdbc.query("SELECT view_json FROM platform_run_views WHERE run_id=? AND last_event_id>0",
        (rs, n) -> JSON.readValue(rs.getString(1), RunObservation.Run.class), runId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  List<RunObservation.Step> steps(String runId) {
    return jdbc.query("SELECT step_json FROM platform_run_steps WHERE run_id=? ORDER BY scheduled_event_id",
        (rs, n) -> JSON.readValue(rs.getString(1), RunObservation.Step.class), runId);
  }

  List<RunObservation.Event> events(String runId, long after, int limit) {
    return jdbc.query("SELECT event_json FROM platform_run_events WHERE run_id=? AND event_id>? ORDER BY event_id LIMIT ?",
        (rs, n) -> JSON.readValue(rs.getString(1), RunObservation.Event.class), runId, after, limit);
  }

  List<RunObservation.ModelAttempt> modelAttempts(String runId) {
    return jdbc.query("""
        SELECT a.*,b.model_profile FROM platform_model_attempts a JOIN platform_model_decisions d ON a.decision_id=d.decision_id
        JOIN platform_run_budgets b ON b.run_id=d.run_id
        WHERE d.run_id=? ORDER BY a.reserved_at,a.decision_id,a.attempt_number
        """, (rs, n) -> new RunObservation.ModelAttempt(rs.getString("decision_id"), rs.getInt("attempt_number"),
        rs.getString("status"), (rs.getString("model_profile") == null ? io.github.mat973252.agentplatform.core.ModelProfile.offline()
            : JSON.readValue(rs.getString("model_profile"), io.github.mat973252.agentplatform.core.ModelProfile.class)).meteringMode(),
        rs.getLong("reserved_at"), rs.getObject("settled_at", Long.class),
        rs.getObject("used_tokens", Long.class), rs.getObject("used_cost_microusd", Long.class)), runId);
  }
}
