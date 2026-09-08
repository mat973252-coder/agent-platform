package io.github.mat973252.agentplatform.permit;

import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A real database test ledger; this never restarts a service or calls a production system. */
public final class JdbcRestartLedger {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public JdbcRestartLedger(DataSource source, Clock clock) {
    jdbc = new JdbcTemplate(source);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    this.clock = clock;
  }

  public RestartReceipt restart(String operationId, String fingerprint, String service) {
    return transaction.execute(status -> {
      long generation = jdbc.queryForObject("SELECT generation FROM demo_service_ledger WHERE service=? FOR UPDATE", Long.class, service);
      var existing = find(operationId);
      if (existing != null) {
        if (!existing.fingerprint().equals(fingerprint) || !existing.service().equals(service)) {
          throw new IllegalStateException("Downstream operation binding mismatch");
        }
        return existing;
      }
      var receipt = new RestartReceipt(operationId, fingerprint, service, generation + 1, clock.millis());
      jdbc.update("INSERT INTO demo_restart_receipts(operation_id,fingerprint,service,generation,committed_at) VALUES (?,?,?,?,?)",
          operationId, fingerprint, service, receipt.generation(), receipt.committedAt());
      jdbc.update("UPDATE demo_service_ledger SET generation=? WHERE service=?", receipt.generation(), service);
      return receipt;
    });
  }

  public RestartReceipt find(String operationId) {
    var rows = jdbc.query("SELECT * FROM demo_restart_receipts WHERE operation_id=?", (rs, row) ->
        new RestartReceipt(rs.getString("operation_id"), rs.getString("fingerprint"), rs.getString("service"),
            rs.getLong("generation"), rs.getLong("committed_at")), operationId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public long generation(String service) {
    return jdbc.queryForObject("SELECT generation FROM demo_service_ledger WHERE service=?", Long.class, service);
  }
}
