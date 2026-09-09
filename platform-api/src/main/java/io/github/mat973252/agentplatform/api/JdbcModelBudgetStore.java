package io.github.mat973252.agentplatform.api;

import io.github.mat973252.agentplatform.core.BudgetContext;
import io.github.mat973252.agentplatform.core.BudgetSnapshot;
import io.github.mat973252.agentplatform.core.ModelUsage;
import io.github.mat973252.agentplatform.core.ModelProfile;
import io.github.mat973252.agentplatform.core.RunBudget;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

final class JdbcModelBudgetStore {
  record Permit(boolean acquired, String output) {}
  private record Decision(String runId, String fingerprint, String output) {}
  private record Attempt(String status, long input, long output, long cost) {}
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final RowMapper<BudgetSnapshot> ROW = (rs, row) -> new BudgetSnapshot(rs.getString("run_id"),
      new RunBudget(rs.getInt("max_model_steps"), rs.getInt("max_duration_seconds"), rs.getLong("max_tokens"),
          rs.getLong("max_cost_microusd")), rs.getLong("deadline"), rs.getLong("used_tokens"),
      rs.getLong("reserved_tokens"), rs.getLong("used_cost_microusd"), rs.getLong("reserved_cost_microusd"),
      rs.getLong("model_attempts"), profile(rs.getString("model_profile")).meteringMode(), profile(rs.getString("model_profile")));

  private static ModelProfile profile(String json) {
    return json == null ? ModelProfile.offline() : JSON.readValue(json, ModelProfile.class);
  }

  JdbcModelBudgetStore(DataSource source, Clock clock) {
    jdbc = new JdbcTemplate(source);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    this.clock = clock;
  }

  BudgetSnapshot open(BudgetContext context) {
    try {
      var limits = context.limits();
      jdbc.update("""
          INSERT INTO platform_run_budgets(run_id,max_model_steps,max_duration_seconds,max_tokens,max_cost_microusd,deadline,model_profile)
          VALUES (?,?,?,?,?,?,?)
          """, context.runId(), limits.maxModelSteps(), limits.maxDurationSeconds(), limits.maxTokens(),
          limits.maxCostMicrousd(), context.deadlineEpochMillis(), JSON.writeValueAsString(context.model()));
    } catch (DuplicateKeyException existing) { /* The original budget must remain unchanged. */ }
    var snapshot = get(context.runId());
    requireBinding(snapshot, context);
    return snapshot;
  }

  BudgetSnapshot get(String runId) {
    var rows = jdbc.query("SELECT * FROM platform_run_budgets WHERE run_id=?", ROW, runId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  Permit reserve(BudgetContext context, String decisionId, int attempt, String fingerprint, ModelUsage quote) {
    requireAttempt(context, decisionId, attempt);
    return transaction.execute(status -> {
      var budget = lock(context);
      var decision = decision(decisionId);
      if (decision != null) {
        if (!decision.runId().equals(context.runId()) || !decision.fingerprint().equals(fingerprint)) {
          throw new BudgetViolation("MODEL_DECISION_BINDING_MISMATCH");
        }
        if (decision.output() != null) return new Permit(false, decision.output());
      }
      if (clock.millis() >= context.deadlineEpochMillis()) throw new BudgetViolation("RUN_TIME_BUDGET_EXCEEDED");
      if (attempt(decisionId, attempt) != null) throw new BudgetViolation("MODEL_ATTEMPT_UNCONFIRMED");
      if (quote.tokens() > budget.limits().maxTokens() - budget.usedTokens() - budget.reservedTokens()) {
        throw new BudgetViolation("TOKEN_BUDGET_EXCEEDED");
      }
      if (quote.costMicrousd() > budget.limits().maxCostMicrousd() - budget.usedCostMicrousd() - budget.reservedCostMicrousd()) {
        throw new BudgetViolation("COST_BUDGET_EXCEEDED");
      }
      if (decision == null) jdbc.update("INSERT INTO platform_model_decisions(decision_id,run_id,fingerprint) VALUES (?,?,?)",
          decisionId, context.runId(), fingerprint);
      jdbc.update("""
          INSERT INTO platform_model_attempts(decision_id,attempt_number,status,input_allowance,output_allowance,cost_allowance,reserved_at)
          VALUES (?,?,'RESERVED',?,?,?,?)
          """, decisionId, attempt, quote.inputTokens(), quote.outputTokens(), quote.costMicrousd(), clock.millis());
      jdbc.update("""
          UPDATE platform_run_budgets SET reserved_tokens=reserved_tokens+?,reserved_cost_microusd=reserved_cost_microusd+?,
            model_attempts=model_attempts+1 WHERE run_id=?
          """, quote.tokens(), quote.costMicrousd(), context.runId());
      return new Permit(true, null);
    });
  }

  String complete(BudgetContext context, String decisionId, int attempt, ModelUsage usage, String output) {
    return transaction.execute(status -> {
      lock(context);
      var decision = decision(decisionId);
      if (decision == null || !decision.runId().equals(context.runId())) throw new BudgetViolation("MODEL_DECISION_BINDING_MISMATCH");
      var reserved = attempt(decisionId, attempt);
      if (reserved == null) throw new BudgetViolation("MODEL_ATTEMPT_NOT_RESERVED");
      if (reserved.status().equals("COMPLETED")) return decision.output();
      if (usage.inputTokens() > reserved.input() || usage.outputTokens() > reserved.output() || usage.costMicrousd() > reserved.cost()) {
        throw new BudgetViolation("MODEL_USAGE_EXCEEDS_ALLOWANCE");
      }
      jdbc.update("""
          UPDATE platform_model_attempts SET status='COMPLETED',used_tokens=?,used_cost_microusd=?,settled_at=?
          WHERE decision_id=? AND attempt_number=?
          """, usage.tokens(), usage.costMicrousd(), clock.millis(), decisionId, attempt);
      jdbc.update("""
          UPDATE platform_run_budgets SET reserved_tokens=reserved_tokens-?,reserved_cost_microusd=reserved_cost_microusd-?,
            used_tokens=used_tokens+?,used_cost_microusd=used_cost_microusd+? WHERE run_id=?
          """, reserved.input() + reserved.output(), reserved.cost(), usage.tokens(), usage.costMicrousd(), context.runId());
      if (output != null) jdbc.update("UPDATE platform_model_decisions SET output=? WHERE decision_id=? AND output IS NULL", output, decisionId);
      return decision(decisionId).output();
    });
  }

  void markUnknown(String decisionId, int attempt) {
    jdbc.update("UPDATE platform_model_attempts SET status='UNKNOWN' WHERE decision_id=? AND attempt_number=? AND status='RESERVED'",
        decisionId, attempt);
  }

  private BudgetSnapshot lock(BudgetContext context) {
    var rows = jdbc.query("SELECT * FROM platform_run_budgets WHERE run_id=? FOR UPDATE", ROW, context.runId());
    if (rows.isEmpty()) throw new BudgetViolation("BUDGET_NOT_INITIALIZED");
    var budget = rows.getFirst();
    requireBinding(budget, context);
    return budget;
  }

  private void requireBinding(BudgetSnapshot budget, BudgetContext context) {
    if (!budget.limits().equals(context.limits()) || budget.deadlineEpochMillis() != context.deadlineEpochMillis()
        || !budget.model().equals(context.model())) {
      throw new BudgetViolation("BUDGET_BINDING_MISMATCH");
    }
  }

  private void requireAttempt(BudgetContext context, String decisionId, int attempt) {
    String prefix = context.runId() + ":model:";
    try {
      if (!decisionId.startsWith(prefix)) throw new NumberFormatException();
      int step = Integer.parseInt(decisionId.substring(prefix.length()));
      if (step < 1 || step > context.limits().maxModelSteps() || attempt < 1 || attempt > 3) throw new NumberFormatException();
    } catch (NumberFormatException invalid) { throw new BudgetViolation("MODEL_ATTEMPT_INVALID"); }
  }

  private Decision decision(String id) {
    var rows = jdbc.query("SELECT run_id,fingerprint,output FROM platform_model_decisions WHERE decision_id=?",
        (rs, row) -> new Decision(rs.getString(1), rs.getString(2), rs.getString(3)), id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private Attempt attempt(String id, int number) {
    var rows = jdbc.query("SELECT status,input_allowance,output_allowance,cost_allowance FROM platform_model_attempts WHERE decision_id=? AND attempt_number=?",
        (rs, row) -> new Attempt(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)), id, number);
    return rows.isEmpty() ? null : rows.getFirst();
  }
}
