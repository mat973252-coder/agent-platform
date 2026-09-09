package io.github.mat973252.agentplatform.api;

import static org.junit.jupiter.api.Assertions.*;
import io.github.mat973252.agentplatform.core.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class JdbcModelBudgetStoreTest {
  private final Instant now = Instant.parse("2026-09-08T00:00:00Z");
  private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
  private final ModelUsage quote = new ModelUsage(10, 5, 20);
  private final ModelUsage actual = new ModelUsage(5, 2, 8);
  private SingleConnectionDataSource source;
  private JdbcModelBudgetStore store;
  private BudgetContext context;

  @BeforeEach
  void setUp() {
    source = new SingleConnectionDataSource("jdbc:h2:mem:" + UUID.randomUUID(), "sa", "", true);
    new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__model_budgets.sql"),
        new ClassPathResource("db/migration/V4__model_profiles.sql")).execute(source);
    store = new JdbcModelBudgetStore(source, clock);
    context = new BudgetContext("run-budget", new RunBudget(6, 30, 30, 40), now.plusSeconds(30).toEpochMilli());
    store.open(context);
  }

  @AfterEach
  void close() { source.destroy(); }

  @Test
  void modelAndPricesRemainBoundOnRecoveryAndLegacyRowsStayOffline() {
    var legacyRequest = tools.jackson.databind.json.JsonMapper.builder().build()
        .readValue("{\"service\":\"orders\",\"approvalTimeoutSeconds\":30}", RunRequest.class);
    assertEquals(ModelProfile.offline(), legacyRequest.model());
    var model = new ModelProfile("OPENAI_COMPATIBLE", "https://example.com/v1", "test-model",
        "diagnostics-prompt-v2", "price-v1", 8192, 512, 3000000, 15000000);
    var remote = new BudgetContext("run-remote", RunBudget.defaults(), context.deadlineEpochMillis(), model);
    assertEquals(model, store.open(remote).model());
    assertEquals("PROVIDER_USAGE_CONFIGURED_ESTIMATE", store.get("run-remote").meteringMode());
    assertThrows(BudgetViolation.class, () -> store.open(new BudgetContext("run-remote", RunBudget.defaults(),
        context.deadlineEpochMillis(), ModelProfile.offline())));
    new org.springframework.jdbc.core.JdbcTemplate(source).update("UPDATE platform_run_budgets SET model_profile=NULL WHERE run_id='run-budget'");
    assertEquals(ModelProfile.offline(), store.open(context).model());
  }

  @Test
  void committedResultIsReusedAcrossWorkersWithoutChargingTwice() {
    assertTrue(store.reserve(context, "run-budget:model:1", 1, "fingerprint", quote).acquired());
    assertEquals("decision", store.complete(context, "run-budget:model:1", 1, actual, "decision"));
    var reopened = new JdbcModelBudgetStore(source, clock);
    var cached = reopened.reserve(context, "run-budget:model:1", 2, "fingerprint", quote);
    assertFalse(cached.acquired());
    assertEquals("decision", cached.output());
    var budget = reopened.get("run-budget");
    assertEquals(7, budget.usedTokens());
    assertEquals(8, budget.usedCostMicrousd());
    assertEquals(0, budget.reservedTokens());
    assertEquals(1, budget.modelAttempts());
    store.complete(context, "run-budget:model:1", 1, actual, "decision");
    assertEquals(budget, store.get("run-budget"));
  }

  @Test
  void unknownAttemptsKeepTheirReservationAndRetriesNeedMoreBudget() {
    store.reserve(context, "run-budget:model:1", 1, "fingerprint", quote);
    store.markUnknown("run-budget:model:1", 1);
    assertThrows(BudgetViolation.class, () -> store.reserve(context, "run-budget:model:1", 1, "fingerprint", quote));
    assertTrue(store.reserve(context, "run-budget:model:1", 2, "fingerprint", quote).acquired());
    var failure = assertThrows(BudgetViolation.class,
        () -> store.reserve(context, "run-budget:model:1", 3, "fingerprint", quote));
    assertEquals("TOKEN_BUDGET_EXCEEDED", failure.reason());
    assertEquals(30, store.get("run-budget").reservedTokens());
    assertEquals(2, store.get("run-budget").modelAttempts());
  }

  @Test
  void overlappingAttemptsChargeBothButKeepTheFirstConfirmedDecision() {
    store.reserve(context, "run-budget:model:1", 1, "fingerprint", quote);
    store.markUnknown("run-budget:model:1", 1);
    store.reserve(context, "run-budget:model:1", 2, "fingerprint", quote);
    assertEquals("second", store.complete(context, "run-budget:model:1", 2, actual, "second"));
    assertEquals("second", store.complete(context, "run-budget:model:1", 1, actual, "late-first"));
    assertEquals(14, store.get("run-budget").usedTokens());
    assertEquals(16, store.get("run-budget").usedCostMicrousd());
    assertEquals(0, store.get("run-budget").reservedTokens());
    assertEquals(2, store.get("run-budget").modelAttempts());
  }

  @Test
  void usageAboveTheAllowanceDoesNotSilentlyReleaseTheReservation() {
    store.reserve(context, "run-budget:model:1", 1, "fingerprint", quote);
    assertEquals("MODEL_USAGE_EXCEEDS_ALLOWANCE", assertThrows(BudgetViolation.class,
        () -> store.complete(context, "run-budget:model:1", 1, new ModelUsage(11, 2, 8), "output")).reason());
    assertEquals(15, store.get("run-budget").reservedTokens());
    assertEquals(0, store.get("run-budget").usedTokens());
  }

  @Test
  void costHasAnIndependentAdmissionLimit() {
    var cheap = new BudgetContext("run-cheap", new RunBudget(6, 30, 1000, 19), context.deadlineEpochMillis());
    store.open(cheap);
    var failure = assertThrows(BudgetViolation.class,
        () -> store.reserve(cheap, "run-cheap:model:1", 1, "fingerprint", quote));
    assertEquals("COST_BUDGET_EXCEEDED", failure.reason());
    assertEquals(0, store.get("run-cheap").modelAttempts());
  }

  @Test
  void deadlinesLimitsAndDecisionInputsCannotBeChangedOnRecovery() {
    assertEquals(store.get("run-budget"), store.open(context));
    assertThrows(BudgetViolation.class, () -> store.open(new BudgetContext("run-budget",
        new RunBudget(6, 30, 300, 400), context.deadlineEpochMillis())));
    store.reserve(context, "run-budget:model:1", 1, "fingerprint", quote);
    assertThrows(BudgetViolation.class, () -> store.reserve(context, "run-budget:model:1", 2, "changed", quote));
    var expired = new JdbcModelBudgetStore(source, Clock.fixed(now.plusSeconds(31), ZoneOffset.UTC));
    assertEquals("RUN_TIME_BUDGET_EXCEEDED", assertThrows(BudgetViolation.class,
        () -> expired.reserve(context, "run-budget:model:2", 1, "fingerprint", quote)).reason());
  }

  @Test
  void concurrentWorkersCannotOverReserveTheSameRun() throws Exception {
    var limited = new BudgetContext("run-limited", new RunBudget(6, 30, 15, 20), context.deadlineEpochMillis());
    store.open(limited);
    var one = new JdbcModelBudgetStore(new DriverManagerDataSource(source.getUrl(), "sa", ""), clock);
    var two = new JdbcModelBudgetStore(new DriverManagerDataSource(source.getUrl(), "sa", ""), clock);
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = threads.submit(() -> reserveAllowed(one, limited, "run-limited:model:1"));
      var second = threads.submit(() -> reserveAllowed(two, limited, "run-limited:model:2"));
      assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
    }
    assertEquals(15, store.get("run-limited").reservedTokens());
    assertEquals(1, store.get("run-limited").modelAttempts());
  }

  private boolean reserveAllowed(JdbcModelBudgetStore worker, BudgetContext budget, String decision) {
    try { return worker.reserve(budget, decision, 1, "fingerprint", quote).acquired(); }
    catch (BudgetViolation exceeded) { return false; }
  }
}
