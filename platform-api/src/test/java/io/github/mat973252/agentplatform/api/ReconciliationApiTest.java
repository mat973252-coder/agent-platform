package io.github.mat973252.agentplatform.api;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "platform.temporal.external=false", "logging.level.io.temporal=ERROR",
    "spring.datasource.url=jdbc:h2:mem:reconciliation-tests;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa",
    "spring.datasource.password=", "platform.security.operator-password=test-operator-password",
    "platform.security.approver-password=test-approver-password", "platform.approval-delivery.interval-ms=100",
    "platform.reconciliation-delivery.interval-ms=100", "platform.demo.failure-mode=FAIL_BEFORE_LEDGER_WRITE"})
@Import(RunApiTest.TemporalTestConfiguration.class)
@Timeout(30)
class ReconciliationApiTest {
  @LocalServerPort private int port;
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void unknownOutcomeCanBeCheckedAndExplicitlyClosedWithoutAnotherExecution() throws Exception {
    String runId = unknownRun();
    String path = "/api/runs/" + runId;
    var operation = send("operator", "GET", path + "/operation", "");
    assertEquals(200, operation.statusCode());
    assertTrue(operation.body().contains("\"receipt\":null"));
    assertEquals(202, send("operator", "POST", path + "/reconciliation", "{\"action\":\"CHECK\"}").statusCode());
    awaitState(runId, "RECONCILIATION_REQUIRED");
    String close = "{\"action\":\"CLOSE\",\"reason\":\"Unable to confirm downstream outcome\"}";
    assertEquals(202, send("operator", "POST", path + "/reconciliation", close).statusCode());
    awaitState(runId, "CLOSED_UNKNOWN");
    assertEquals(202, send("operator", "POST", path + "/reconciliation", close).statusCode());
    var closed = send("operator", "GET", path + "/operation", "").body();
    assertTrue(closed.contains("\"closedBy\":\"operator\""));
    assertTrue(closed.contains("\"receipt\":null"));
    assertTrue(closed.contains("\"serviceGeneration\":0"));
  }

  @Test
  void closingAnUnknownOutcomeRequiresAnOperatorAndAReason() throws Exception {
    String runId = unknownRun();
    String path = "/api/runs/" + runId + "/reconciliation";
    assertEquals(403, send("approver", "POST", path, "{\"action\":\"CLOSE\",\"reason\":\"test\"}").statusCode());
    assertEquals(400, send("operator", "POST", path, "{\"action\":\"CLOSE\"}").statusCode());
    awaitState(runId, "RECONCILIATION_REQUIRED");
    assertEquals(202, send("operator", "POST", path, "{\"action\":\"CLOSE\",\"reason\":\"Test completed without confirmed outcome\"}").statusCode());
    awaitState(runId, "CLOSED_UNKNOWN");
  }

  private String unknownRun() throws Exception {
    String id = UUID.randomUUID().toString();
    String runId = "run-" + id;
    assertEquals(202, send("operator", "POST", "/api/runs",
        "{\"requestId\":\"" + id + "\",\"service\":\"orders\",\"approvalTimeoutSeconds\":300}").statusCode());
    awaitState(runId, "WAITING_APPROVAL");
    assertEquals(202, send("approver", "POST", "/api/runs/" + runId + "/approval",
        "{\"approvalId\":\"" + runId + ":approval:restart\",\"decision\":\"APPROVE\"}").statusCode());
    awaitState(runId, "RECONCILIATION_REQUIRED");
    return runId;
  }

  private void awaitState(String runId, String state) throws Exception {
    for (int attempt = 0; attempt < 100; attempt++) {
      if (send("operator", "GET", "/api/runs/" + runId, "").body().contains("\"state\":\"" + state + "\"")) return;
      Thread.sleep(50);
    }
    fail("Run did not reach " + state);
  }

  private HttpResponse<String> send(String actor, String method, String path, String body) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
            (actor + ":test-" + actor + "-password").getBytes(StandardCharsets.UTF_8)))
        .header("Content-Type", "application/json").header("X-Platform-Request", "true")
        .method(method, HttpRequest.BodyPublishers.ofString(body)).build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
