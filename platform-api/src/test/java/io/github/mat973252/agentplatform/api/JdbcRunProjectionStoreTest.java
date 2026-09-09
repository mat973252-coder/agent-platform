package io.github.mat973252.agentplatform.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class JdbcRunProjectionStoreTest {
  private JdbcRunProjectionStore store;
  private JdbcTemplate jdbc;

  @BeforeEach
  void prepare() {
    var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    new ResourceDatabasePopulator(new ClassPathResource("db/migration/V5__run_projections.sql")).execute(source);
    jdbc = new JdbcTemplate(source);
    store = new JdbcRunProjectionStore(source);
  }

  @Test
  void duplicateAndOlderRefreshCannotDuplicateEventsOrRegressTerminalRun() {
    var full = RunHistoryProjection.map("run-test", "execution-1", RunHistoryProjectionTest.history(), 2000);
    store.save(full);
    store.save(full);
    store.save(RunHistoryProjection.map("run-test", "execution-1", RunHistoryProjectionTest.history().subList(0, 2), 1000));
    assertEquals(5, store.events("run-test", 0, 100).size());
    assertEquals("COMPLETED", store.get("run-test").executionStatus());
    assertEquals(1, store.steps("run-test").size());
    assertEquals(3, store.events("run-test", 2, 100).getFirst().eventId());
  }

  @Test
  void rebuildRepairsMissingRowsWithoutChangingEventIds() {
    var full = RunHistoryProjection.map("run-test", "execution-1", RunHistoryProjectionTest.history(), 2000);
    store.save(full);
    var original = store.events("run-test", 0, 100);
    jdbc.update("DELETE FROM platform_run_events WHERE run_id=? AND event_id=?", "run-test", 3);
    jdbc.update("DELETE FROM platform_run_steps WHERE run_id=?", "run-test");
    store.save(full);
    assertEquals(original, store.events("run-test", 0, 100));
    assertEquals(1, store.steps("run-test").size());
  }

  @Test
  void executionIdCannotBeSilentlyRebound() {
    store.save(RunHistoryProjection.map("run-test", "execution-1", RunHistoryProjectionTest.history(), 2000));
    assertThrows(IllegalStateException.class, () -> store.save(
        RunHistoryProjection.map("run-test", "execution-2", RunHistoryProjectionTest.history(), 3000)));
    assertEquals("execution-1", store.get("run-test").executionId());
  }

  @Test
  void competingConnectionsKeepOneCompleteMonotonicProjection() throws Exception {
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> {
        start.await();
        store.save(RunHistoryProjection.map("run-test", "execution-1", RunHistoryProjectionTest.history(), 2000));
        return null;
      });
      var second = executor.submit(() -> {
        start.await();
        store.save(RunHistoryProjection.map("run-test", "execution-1", RunHistoryProjectionTest.history().subList(0, 2), 1000));
        return null;
      });
      start.countDown();
      first.get(5, java.util.concurrent.TimeUnit.SECONDS);
      second.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertEquals("COMPLETED", store.get("run-test").executionStatus());
    assertEquals(5, store.events("run-test", 0, 100).size());
    assertEquals(3, store.steps("run-test").getFirst().lastRecordedAttempt());
  }
}
