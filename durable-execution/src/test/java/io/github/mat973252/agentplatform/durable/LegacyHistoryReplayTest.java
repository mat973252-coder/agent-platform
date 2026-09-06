package io.github.mat973252.agentplatform.durable;

import io.temporal.testing.WorkflowReplayer;
import io.temporal.common.WorkflowExecutionHistory;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LegacyHistoryReplayTest {
  @ParameterizedTest
  @ValueSource(strings = {"p0-waiting.json", "p0-approved.json"})
  void p0HistoriesRemainReplayable(String fixture) throws Exception {
    try (var stream = getClass().getResourceAsStream("/history/" + fixture)) {
      var history = WorkflowExecutionHistory.fromJson(
          new String(stream.readAllBytes(), StandardCharsets.UTF_8), "run-p0-history-fixture");
      WorkflowReplayer.replayWorkflowExecution(history, DiagnosticsWorkflowImpl.class);
    }
  }
}
