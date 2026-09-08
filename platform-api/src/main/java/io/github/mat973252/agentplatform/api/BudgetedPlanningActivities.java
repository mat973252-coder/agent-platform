package io.github.mat973252.agentplatform.api;

import io.github.mat973252.agentplatform.core.AgentContext;
import io.github.mat973252.agentplatform.core.AgentDecision;
import io.github.mat973252.agentplatform.core.BudgetContext;
import io.github.mat973252.agentplatform.core.ModelUsage;
import io.github.mat973252.agentplatform.durable.BudgetActivities;
import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class BudgetedPlanningActivities implements BudgetActivities {
  // These are synthetic fixture units, not a provider's token count or bill.
  private static final ModelUsage ALLOWANCE = new ModelUsage(2048, 512, 5000);
  private static final ModelUsage USAGE = new ModelUsage(800, 128, 1000);
  private final DemoAgentActivities planner;
  private final JdbcModelBudgetStore budgets;
  private final ModelFailureMode failure;
  private final ModelDecisionCodec codec = new ModelDecisionCodec();
  private final JsonMapper mapper = JsonMapper.builder().build();

  BudgetedPlanningActivities(DemoAgentActivities planner, JdbcModelBudgetStore budgets,
      @Value("${platform.demo.model-failure-mode:NONE}") ModelFailureMode failure) {
    this.planner = planner;
    this.budgets = budgets;
    this.failure = failure;
  }

  @Override
  public void initializeBudget(BudgetContext context) {
    requireRun(context);
    try { budgets.open(context); }
    catch (BudgetViolation violation) { throw rejected(violation); }
  }

  @Override
  public AgentDecision planWithinBudget(AgentContext context, BudgetContext budget) {
    requireRun(budget);
    int attempt = Activity.getExecutionContext().getInfo().getAttempt();
    try {
      var permit = budgets.reserve(budget, context.decisionId(), attempt, fingerprint(context), ALLOWANCE);
      if (!permit.acquired()) return codec.decode(permit.output(), context.service());
      return executeReserved(context, budget, attempt);
    } catch (BudgetViolation violation) { throw rejected(violation); }
  }

  private AgentDecision executeReserved(AgentContext context, BudgetContext budget, int attempt) {
    try {
      String response = planner.response(context);
      afterResponse(context.decisionId());
      try { codec.decode(response, context.service()); }
      catch (IllegalArgumentException invalid) {
        budgets.complete(budget, context.decisionId(), attempt, USAGE, null);
        throw ApplicationFailure.newNonRetryableFailure("Model decision rejected", "MODEL_OUTPUT_INVALID");
      }
      String saved = budgets.complete(budget, context.decisionId(), attempt, USAGE, response);
      if (failure == ModelFailureMode.FAIL_AFTER_BUDGET_COMMIT) throw new IllegalStateException("Synthetic response loss after settlement");
      return codec.decode(saved, context.service());
    } catch (RuntimeException uncertain) {
      budgets.markUnknown(context.decisionId(), attempt);
      throw uncertain;
    }
  }

  private void requireRun(BudgetContext budget) {
    if (!Activity.getExecutionContext().getInfo().getWorkflowId().equals(budget.runId())) {
      throw ApplicationFailure.newNonRetryableFailure("Budget belongs to another Run", "BUDGET_BINDING_MISMATCH");
    }
  }

  private String fingerprint(AgentContext context) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(context))); }
    catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
  }

  private ApplicationFailure rejected(BudgetViolation violation) {
    return ApplicationFailure.newNonRetryableFailure("Model budget rejected", violation.reason());
  }

  private void afterResponse(String decisionId) {
    if (failure == ModelFailureMode.FAIL_AFTER_MODEL_RESPONSE) throw new IllegalStateException("Synthetic unconfirmed model response");
    if (failure == ModelFailureMode.PAUSE_AFTER_MODEL_RESPONSE) {
      LoggerFactory.getLogger(BudgetedPlanningActivities.class).info("SYNTHETIC_MODEL_RESPONSE_PAUSED {}", decisionId);
      try { Thread.sleep(300_000); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
  }
}
