package io.github.mat973252.agentplatform.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.mat973252.agentplatform.core.*;
import io.github.mat973252.agentplatform.durable.BudgetActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@Timeout(20)
class BudgetedPlanningActivitiesTest {
  private final Instant now = Instant.parse("2026-09-08T00:00:00Z");
  private final DemoAgentActivities planner = mock(DemoAgentActivities.class);
  private final SpringAiPlanner remote = mock(SpringAiPlanner.class);
  private SingleConnectionDataSource source;
  private JdbcModelBudgetStore store;
  private TestWorkflowEnvironment environment;
  private final AgentContext input = new AgentContext("run-probe:model:1", "orders", "evidence", "runbook", null, false, false);
  private final String response = """
      {"schemaVersion":"1","action":"FINISH","tool":null,"arguments":{},"message":"Offline complete"}
      """;

  @BeforeEach
  void setUp() {
    source = new SingleConnectionDataSource("jdbc:h2:mem:" + UUID.randomUUID(), "sa", "", true);
    new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__model_budgets.sql"),
        new ClassPathResource("db/migration/V4__model_profiles.sql")).execute(source);
    store = new JdbcModelBudgetStore(source, Clock.fixed(now, ZoneOffset.UTC));
    when(planner.response(any())).thenReturn(response);
  }

  @AfterEach
  void close() {
    if (environment != null) environment.close();
    source.destroy();
  }

  @Test
  void realUsageIsSettledAndCachedRetryDoesNotCallTheProviderAgain() {
    when(remote.call(any(), any(), any())).thenReturn(new SpringAiPlanner.Reply(response, new ModelUsage(1000, 100, 4500)));
    var workflow = start(ModelFailureMode.FAIL_AFTER_BUDGET_COMMIT);
    assertEquals("FINISH", workflow.run(remoteBudget(), input).action());
    assertEquals(1100, store.get("run-probe").usedTokens());
    assertEquals(4500, store.get("run-probe").usedCostMicrousd());
    assertEquals(0, store.get("run-probe").reservedTokens());
    verify(remote, times(1)).call(any(), any(), any());
    verifyNoInteractions(planner);
  }

  @Test
  void missingProviderUsageKeepsTheFullReservationWithoutOfflineFallback() {
    when(remote.call(any(), any(), any())).thenThrow(ApplicationFailure.newNonRetryableFailure("Missing usage", "MODEL_USAGE_UNKNOWN"));
    var failure = assertThrows(WorkflowFailedException.class, () -> start(ModelFailureMode.NONE).run(remoteBudget(), input));
    assertEquals("MODEL_USAGE_UNKNOWN", ((ApplicationFailure) failure.getCause().getCause()).getType());
    assertEquals(8704, store.get("run-probe").reservedTokens());
    assertEquals(0, store.get("run-probe").usedTokens());
    verify(remote, times(1)).call(any(), any(), any());
    verifyNoInteractions(planner);
  }

  private BudgetContext remoteBudget() {
    var model = new ModelProfile("OPENAI_COMPATIBLE", "https://example.com/v1", "test-model",
        "diagnostics-prompt-v2", "price-v1", 8192, 512, 3000000, 15000000);
    return new BudgetContext("run-probe", RunBudget.defaults(), now.plusSeconds(60).toEpochMilli(), model);
  }

  @Test
  void aRetryAfterSettlementReadsTheCachedDecisionWithoutAnotherModelCall() {
    var workflow = start(ModelFailureMode.FAIL_AFTER_BUDGET_COMMIT);
    assertEquals("FINISH", workflow.run(budget(100000), input).action());
    var usage = store.get("run-probe");
    assertEquals(928, usage.usedTokens());
    assertEquals(1000, usage.usedCostMicrousd());
    assertEquals(0, usage.reservedTokens());
    assertEquals(1, usage.modelAttempts());
    verify(planner, times(1)).response(any());
  }

  @Test
  void lostResponsesConsumeTheAllowanceAndBlockAnUnaffordableRetry() {
    var workflow = start(ModelFailureMode.FAIL_AFTER_MODEL_RESPONSE);
    var failure = assertThrows(WorkflowFailedException.class, () -> workflow.run(budget(6000), input));
    assertEquals("TOKEN_BUDGET_EXCEEDED", ((ApplicationFailure) failure.getCause().getCause()).getType());
    var usage = store.get("run-probe");
    assertEquals(0, usage.usedTokens());
    assertEquals(5120, usage.reservedTokens());
    assertEquals(10000, usage.reservedCostMicrousd());
    assertEquals(2, usage.modelAttempts());
    verify(planner, times(2)).response(any());
  }

  @Test
  void invalidModelOutputStillConsumesItsReportedUsage() {
    when(planner.response(any())).thenReturn("invalid json");
    var workflow = start(ModelFailureMode.NONE);
    var failure = assertThrows(WorkflowFailedException.class, () -> workflow.run(budget(100000), input));
    assertEquals("MODEL_OUTPUT_INVALID", ((ApplicationFailure) failure.getCause().getCause()).getType());
    assertEquals(928, store.get("run-probe").usedTokens());
    assertEquals(0, store.get("run-probe").reservedTokens());
    verify(planner, times(1)).response(any());
  }

  private BudgetContext budget(long tokens) {
    return new BudgetContext("run-probe", new RunBudget(6, 60, tokens, 1000000), now.plusSeconds(60).toEpochMilli());
  }

  private ProbeWorkflow start(ModelFailureMode failure) {
    environment = TestWorkflowEnvironment.newInstance();
    var worker = environment.newWorker("budget-probe");
    worker.registerWorkflowImplementationTypes(Probe.class);
    worker.registerActivitiesImplementations(new BudgetedPlanningActivities(planner, store, failure, remote));
    environment.start();
    return environment.getWorkflowClient().newWorkflowStub(ProbeWorkflow.class,
        WorkflowOptions.newBuilder().setWorkflowId("run-probe").setTaskQueue("budget-probe").build());
  }

  @WorkflowInterface
  public interface ProbeWorkflow {
    @WorkflowMethod AgentDecision run(BudgetContext budget, AgentContext input);
  }

  public static class Probe implements ProbeWorkflow {
    private final BudgetActivities activities = Workflow.newActivityStub(BudgetActivities.class,
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5))
            .setRetryOptions(RetryOptions.newBuilder().setInitialInterval(Duration.ofSeconds(1)).setMaximumAttempts(3).build()).build());
    public AgentDecision run(BudgetContext budget, AgentContext input) {
      activities.initializeBudget(budget);
      return activities.planWithinBudget(input, budget);
    }
  }
}
