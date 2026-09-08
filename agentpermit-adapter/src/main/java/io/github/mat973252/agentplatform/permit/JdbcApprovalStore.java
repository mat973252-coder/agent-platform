package io.github.mat973252.agentplatform.permit;

import io.github.mat973252.agentplatform.core.ApprovalDecision;
import io.github.mat973252.agentplatform.core.ApprovalState;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Application-owned approval records; delivery intent is committed in the same row as the decision. */
public final class JdbcApprovalStore {
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private static final RowMapper<ApprovalRecord> ROW = (rs, row) -> new ApprovalRecord(
      rs.getString("approval_id"), rs.getString("run_id"), rs.getString("operation_id"),
      rs.getString("step_id"), rs.getString("tool_name"), rs.getString("resource_id"),
      rs.getString("fingerprint"), rs.getLong("expires_at"), rs.getString("status"),
      rs.getString("decided_by"), rs.getObject("decided_at", Long.class),
      rs.getString("cancelled_by"), rs.getObject("cancelled_at", Long.class), rs.getBoolean("execution_started"));

  public JdbcApprovalStore(DataSource source, Clock clock) {
    this.jdbc = new JdbcTemplate(source);
    this.clock = clock;
  }

  public ApprovalRecord create(String id, String runId, String operationId, String service,
      String fingerprint, long expiresAt) {
    try {
      jdbc.update("""
          INSERT INTO platform_approvals
            (approval_id,run_id,operation_id,step_id,tool_name,resource_id,fingerprint,expires_at,status)
          VALUES (?,?,?,'restart:1','ops.restart',?,?,?,'PENDING')
          """, id, runId, operationId, service, fingerprint, expiresAt);
    } catch (DuplicateKeyException duplicate) {
      // Activity retries must retain the exact original request, including its deadline.
    }
    var saved = get(id);
    if (!saved.runId().equals(runId) || !saved.operationId().equals(operationId)
        || !saved.resourceId().equals(service) || !saved.fingerprint().equals(fingerprint)
        || saved.expiresAt() != expiresAt) {
      throw new IllegalStateException("Approval request binding conflicts with the original");
    }
    return saved;
  }

  public ApprovalRecord get(String id) {
    var rows = jdbc.query("SELECT * FROM platform_approvals WHERE approval_id=?", ROW, id);
    if (rows.isEmpty()) throw new IllegalStateException("Approval record was not found");
    return rows.getFirst();
  }

  public ApprovalRecord findByRun(String runId) {
    var rows = jdbc.query("SELECT * FROM platform_approvals WHERE run_id=?", ROW, runId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public List<ApprovalRecord> pending() {
    return jdbc.query("SELECT * FROM platform_approvals WHERE status='PENDING' AND expires_at>? ORDER BY expires_at LIMIT 100",
        ROW, clock.millis());
  }

  public void decide(String runId, String id, ApprovalDecision decision, String actor) {
    long now = clock.millis();
    int changed = jdbc.update("""
        UPDATE platform_approvals SET status=?,decided_by=?,decided_at=?,delivered=FALSE
        WHERE approval_id=? AND run_id=? AND status='PENDING' AND expires_at>?
        """, decision.name(), actor, now, id, runId, now);
    if (changed == 1) return;
    var saved = get(id);
    if (!saved.runId().equals(runId) || !saved.status().equals(decision.name())
        || !actor.equals(saved.decidedBy()) || saved.expiresAt() <= now) {
      throw new IllegalStateException("Approval is expired, closed, or already has another decision");
    }
  }

  public void cancel(String runId, String id, String actor) {
    long now = clock.millis();
    int changed = jdbc.update("""
        UPDATE platform_approvals SET status='CANCELLED',cancelled_by=?,cancelled_at=?,delivered=FALSE
        WHERE approval_id=? AND run_id=? AND execution_started=FALSE AND status IN ('PENDING','APPROVE') AND expires_at>?
        """, actor, now, id, runId, now);
    if (changed == 0 && !(get(id).runId().equals(runId) && get(id).status().equals("CANCELLED"))) {
      throw new IllegalStateException("Approval is closed or execution has already begun");
    }
  }

  public boolean valid(ApprovalRecord record, String fingerprint, String approver) {
    return record.status().equals("APPROVE") && record.expiresAt() > clock.millis()
        && record.fingerprint().equals(fingerprint) && approver.equals(record.decidedBy());
  }

  public ApprovalState resolution(String id) {
    var record = get(id);
    var state = ApprovalState.valueOf(record.status());
    if ((state == ApprovalState.PENDING || state == ApprovalState.APPROVE) && record.expiresAt() <= clock.millis()) {
      return ApprovalState.EXPIRED;
    }
    return state;
  }

  public void claim(String id, String fingerprint, String approver) {
    // This is the cancellation ordering boundary, not a cross-Worker idempotency guarantee.
    int changed = jdbc.update("""
        UPDATE platform_approvals SET execution_started=TRUE
        WHERE approval_id=? AND fingerprint=? AND decided_by=? AND status='APPROVE' AND expires_at>?
        """, id, fingerprint, approver, clock.millis());
    if (changed == 0) throw new IllegalStateException("Approval was revoked or expired before execution");
  }

  public List<ApprovalRecord> undelivered() {
    return jdbc.query("SELECT * FROM platform_approvals WHERE status<>'PENDING' AND delivered=FALSE ORDER BY COALESCE(cancelled_at,decided_at) LIMIT 100", ROW);
  }

  public void delivered(String id, String status) {
    // An old APPROVE delivery must not acknowledge a newer CANCELLED event.
    jdbc.update("UPDATE platform_approvals SET delivered=TRUE WHERE approval_id=? AND status=?", id, status);
  }
}
