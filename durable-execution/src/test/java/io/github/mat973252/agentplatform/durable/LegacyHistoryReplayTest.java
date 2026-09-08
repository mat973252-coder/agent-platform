package io.github.mat973252.agentplatform.durable;

import io.temporal.testing.WorkflowReplayer;
import io.temporal.common.WorkflowExecutionHistory;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LegacyHistoryReplayTest {
  @ParameterizedTest
  @ValueSource(strings = {"p0-waiting.json", "p0-approved.json", "p1-waiting.json", "p1-approved.json",
      "p12-waiting.json", "p12-approved.json"})
  void oldHistoriesRemainReplayable(String fixture) throws Exception {
    try (var stream = getClass().getResourceAsStream("/history/" + fixture)) {
      var history = WorkflowExecutionHistory.fromJson(
          new String(stream.readAllBytes(), StandardCharsets.UTF_8), "run-" + fixture.split("-")[0] + "-history-fixture");
      WorkflowReplayer.replayWorkflowExecution(history, DiagnosticsWorkflowImpl.class);
    }
  }
}
