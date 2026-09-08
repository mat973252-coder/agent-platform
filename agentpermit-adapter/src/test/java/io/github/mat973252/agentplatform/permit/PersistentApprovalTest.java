package io.github.mat973252.agentplatform.permit;

import static org.junit.jupiter.api.Assertions.*;

import io.github.mat973252.agentplatform.core.ApprovalDecision;
import io.github.mat973252.agentplatform.core.ActionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;

class PersistentApprovalTest {
  private final Instant now = Instant.parse("2026-09-08T00:00:00Z");
  private SingleConnectionDataSource source;
  private JdbcApprovalStore store;
  private PersistentGovernance governance;

  @BeforeEach
  void setUp() {
    // Keep the DDL session open for H2 2.4.240 CHECK constraints (h2database/h2database#4291).
    source = new SingleConnectionDataSource("jdbc:h2:mem:" + UUID.randomUUID(), "sa", "", true);
    new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__approvals.sql")).execute(source);
    store = open(now);
    governance = new PersistentGovernance(store, "approver", true);
  }

  @AfterEach
  void close() { source.destroy(); }

  private JdbcApprovalStore open(Instant time) {
    return new JdbcApprovalStore(source, Clock.fixed(time, ZoneOffset.UTC));
  }

  private void prepare() {
    governance.prepare("run-1", "run-1:restart:1", "orders", "approval-1", now.plusSeconds(30).toEpochMilli());
  }

  @Test
  void creationRetriesKeepTheOriginalBindingAndExpiry() {
    prepare();
    prepare();
    assertEquals(1, store.pending().size());
    assertThrows(IllegalStateException.class, () -> governance.prepare(
        "run-1", "run-1:restart:2", "orders", "approval-1", now.plusSeconds(30).toEpochMilli()));
    assertThrows(IllegalStateException.class, () -> governance.prepare(
        "run-1", "run-1:restart:1", "orders", "approval-1", now.plusSeconds(60).toEpochMilli()));
  }

  @Test
  void decisionAndDeliverySurviveAStoreRestart() {
    prepare();
    store.decide("run-1", "approval-1", ApprovalDecision.APPROVE, "approver");
    var recovered = open(now);
    assertEquals("approver", recovered.get("approval-1").decidedBy());
    assertEquals(1, recovered.undelivered().size());
    recovered.delivered("approval-1", "APPROVE");
    assertTrue(store.undelivered().isEmpty());
    assertEquals(ActionStatus.EXECUTED, new PersistentGovernance(recovered, "approver", true)
        .restart("run-1:restart:1", "orders", "approval-1").status());
  }

  @Test
  void theFirstDecisionWinsAndDuplicateDecisionIsIdempotent() {
    prepare();
    store.decide("run-1", "approval-1", ApprovalDecision.REJECT, "approver");
    store.decide("run-1", "approval-1", ApprovalDecision.REJECT, "approver");
    assertThrows(IllegalStateException.class, () -> store.decide("run-1", "approval-1", ApprovalDecision.APPROVE, "approver"));
    assertNull(governance.restart("run-1:restart:1", "orders", "approval-1").output());
  }

  @Test
  void changedParametersResourceApproverOrPolicyCannotExecute() {
    prepare();
    store.decide("run-1", "approval-1", ApprovalDecision.APPROVE, "approver");
    assertNull(governance.restart("run-1:restart:2", "orders", "approval-1").output());
    assertNull(governance.restart("run-1:restart:1", "other", "approval-1").output());
    assertNull(new PersistentGovernance(store, "new-approver", true).restart("run-1:restart:1", "orders", "approval-1").output());
    assertNull(new PersistentGovernance(store, "approver", false).restart("run-1:restart:1", "orders", "approval-1").output());
    assertFalse(store.get("approval-1").executionStarted());
  }

  @Test
  void expiryIsRecheckedAfterRecoveryEvenIfAlreadyApproved() {
    prepare();
    store.decide("run-1", "approval-1", ApprovalDecision.APPROVE, "approver");
    var expired = open(now.plusSeconds(30));
    assertNull(new PersistentGovernance(expired, "approver", true).restart("run-1:restart:1", "orders", "approval-1").output());
    assertThrows(IllegalStateException.class, () -> expired.decide("run-1", "approval-1", ApprovalDecision.APPROVE, "approver"));
  }

  @Test
  void anExpiredRequestCannotBeChangedIntoACancellation() {
    prepare();
    assertThrows(IllegalStateException.class, () -> open(now.plusSeconds(30)).cancel("run-1", "approval-1", "operator"));
    assertEquals("PENDING", store.get("approval-1").status());
  }

  @Test
  void anAcceptedDecisionIsNotReportedAsFailedWhenTimeCrossesTheDeadline() {
    prepare();
    var reads = new AtomicInteger();
    Clock advancing = new Clock() {
      @Override public ZoneId getZone() { return ZoneOffset.UTC; }
      @Override public Clock withZone(ZoneId zone) { return this; }
      @Override public Instant instant() { return now.plusSeconds(30L * reads.getAndIncrement()); }
    };
    var edge = new JdbcApprovalStore(source, advancing);
    assertDoesNotThrow(() -> edge.decide("run-1", "approval-1", ApprovalDecision.APPROVE, "approver"));
    assertEquals("APPROVE", store.get("approval-1").status());
  }

  @Test
  void cancellationBeforeExecutionRevokesApprovalAndStaleDeliveryCannotHideIt() {
    prepare();
    store.decide("run-1", "approval-1", ApprovalDecision.APPROVE, "approver");
    store.cancel("run-1", "approval-1", "operator");
    store.delivered("approval-1", "APPROVE");
    assertEquals("CANCELLED", store.undelivered().getFirst().status());
    assertEquals("approver", store.get("approval-1").decidedBy(), "Cancellation retains the approval audit identity");
    assertEquals("operator", store.get("approval-1").cancelledBy());
    assertNull(governance.restart("run-1:restart:1", "orders", "approval-1").output());
    assertThrows(IllegalStateException.class, () -> store.decide("run-1", "approval-1", ApprovalDecision.APPROVE, "approver"));
  }

  @Test
  void cancellationAfterExecutionBeginsCannotPromiseRollback() {
    prepare();
    store.decide("run-1", "approval-1", ApprovalDecision.APPROVE, "approver");
    assertEquals(ActionStatus.EXECUTED, governance.restart("run-1:restart:1", "orders", "approval-1").status());
    assertThrows(IllegalStateException.class, () -> store.cancel("run-1", "approval-1", "operator"));
  }

  @Test
  void concurrentCancellationAndExecutionHaveOnlyOneWinner() throws Exception {
    prepare();
    store.decide("run-1", "approval-1", ApprovalDecision.APPROVE, "approver");
    var independent = new JdbcApprovalStore(
        new DriverManagerDataSource(source.getUrl(), "sa", ""), Clock.fixed(now, ZoneOffset.UTC));
    var fingerprint = store.get("approval-1").fingerprint();
    var start = new CountDownLatch(1);
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var cancelled = threads.submit(() -> {
        start.await();
        try { independent.cancel("run-1", "approval-1", "operator"); return true; }
        catch (IllegalStateException conflict) { return false; }
      });
      var started = threads.submit(() -> {
        start.await();
        try { store.claim("approval-1", fingerprint, "approver"); return true; }
        catch (IllegalStateException conflict) { return false; }
      });
      start.countDown();
      assertNotEquals(cancelled.get(), started.get());
    }
  }
}
