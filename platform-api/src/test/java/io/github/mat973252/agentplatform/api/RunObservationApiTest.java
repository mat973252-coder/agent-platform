package io.github.mat973252.agentplatform.api;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"platform.temporal.external=false", "logging.level.io.temporal=ERROR",
        "spring.datasource.url=jdbc:h2:mem:observation-api;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa",
        "spring.datasource.password=", "platform.security.operator-password=test-operator-password",
        "platform.security.approver-password=test-approver-password", "platform.approval-delivery.interval-ms=100"})
@Import(RunApiTest.TemporalTestConfiguration.class)
@Timeout(30)
class RunObservationApiTest {
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired io.temporal.client.WorkflowClient workflowClient;
  private final HttpClient http = HttpClient.newHttpClient();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void authenticatedHistoryAndSseResumeWithoutGapsAndRebuildWithoutExecutingTools() throws Exception {
    String id = UUID.randomUUID().toString();
    String run = "run-" + id;
    assertEquals(202, send("operator", "POST", "", """
        {"requestId":"%s","service":"orders","approvalTimeoutSeconds":300}
        """.formatted(id)).statusCode());
    await(run, "WAITING_APPROVAL");
    var pending = send("approver", "GET", "/" + run + "/history", "");
    assertEquals(200, pending.statusCode());
    assertTrue(pending.body().contains(run + ":model:1"));
    assertTrue(pending.body().contains("lastRecordedAttempt"));
    assertFalse(pending.body().contains("runbook"));
    var firstPage = JSON.readTree(send("operator", "GET", "/" + run + "/events?limit=2", "").body());
    assertEquals(2, firstPage.path("events").size());
    String cursor = firstPage.path("nextCursor").asText();
    assertTrue(cursor.endsWith(":2"));
    assertEquals(400, send("operator", "GET", "/" + run + "/events?after=other:2", "").statusCode());
    assertEquals(400, send("operator", "GET", "/" + run + "/events?limit=0", "").statusCode());
    assertEquals(202, send("approver", "POST", "/" + run + "/approval",
        "{\"approvalId\":\"" + run + ":approval:restart\",\"decision\":\"APPROVE\"}").statusCode());
    await(run, "SUCCEEDED");
    var detail = JSON.readTree(send("operator", "GET", "/" + run + "/history", "").body());
    assertEquals("COMPLETED", detail.path("run").path("executionStatus").asText());
    assertEquals("SUCCEEDED", detail.path("run").path("businessState").asText());
    assertEquals(3, detail.path("modelAttempts").size());
    assertEquals("OFFLINE_SIMULATED", detail.path("modelAttempts").get(0).path("meteringMode").asText());
    var request = request("approver", "GET", "/" + run + "/events/stream", "")
        .header("Last-Event-ID", cursor).build();
    var stream = http.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, stream.statusCode());
    assertTrue(stream.headers().firstValue("Content-Type").orElseThrow().startsWith("text/event-stream"));
    var ids = stream.body().lines().filter(line -> line.startsWith("id:")).map(line -> line.substring(3).trim()).toList();
    long finalId = detail.path("run").path("lastEventId").asLong();
    assertEquals(finalId - 2, ids.size());
    for (int i = 0; i < ids.size(); i++) assertEquals(cursor.substring(0, cursor.lastIndexOf(':') + 1) + (i + 3), ids.get(i));
    assertFalse(stream.body().contains("test-approver-password"));
    var before = send("operator", "GET", "/" + run + "/events?limit=500", "").body();
    jdbc.update("DELETE FROM platform_run_events WHERE run_id=?", run);
    jdbc.update("DELETE FROM platform_run_steps WHERE run_id=?", run);
    jdbc.update("DELETE FROM platform_run_views WHERE run_id=?", run);
    assertEquals(before, send("operator", "GET", "/" + run + "/events?limit=500", "").body());
    assertEquals(3, JSON.readTree(send("operator", "GET", "/" + run + "/budget", "").body()).path("modelAttempts").asInt());
    var unsupported = assertThrows(io.grpc.StatusRuntimeException.class, () -> workflowClient.getWorkflowServiceStubs()
        .blockingStub().listWorkflowExecutions(io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest.newBuilder()
            .setNamespace(workflowClient.getOptions().getNamespace()).setPageSize(1)
            .setQuery("WorkflowType = 'DiagnosticsWorkflow'").build()));
    assertEquals(io.grpc.Status.Code.UNIMPLEMENTED, unsupported.getStatus().getCode());
    assertEquals(503, send("operator", "GET", "?limit=1", "").statusCode());
  }

  @Test
  void projectionOutageDoesNotBlockWorkflowAndRecoveryRepairsHistory() throws Exception {
    String id = UUID.randomUUID().toString();
    String run = "run-" + id;
    jdbc.execute("ALTER TABLE platform_run_views RENAME TO unavailable_run_views");
    try {
      assertEquals(202, send("operator", "POST", "", """
          {"requestId":"%s","service":"orders","approvalTimeoutSeconds":300}
          """.formatted(id)).statusCode());
      await(run, "WAITING_APPROVAL");
      var failed = send("operator", "GET", "/" + run + "/history", "");
      assertEquals(503, failed.statusCode());
      assertFalse(failed.body().contains("SELECT"));
      assertEquals(202, send("operator", "POST", "/" + run + "/cancel", "").statusCode());
      await(run, "CANCELLED");
    } finally {
      jdbc.execute("ALTER TABLE unavailable_run_views RENAME TO platform_run_views");
    }
    assertEquals(200, send("operator", "GET", "/" + run + "/history", "").statusCode());
  }

  @Test
  void missingAndUnauthorizedObservationRequestsHaveExplicitStatus() throws Exception {
    assertEquals(401, send(null, "GET", "/missing/history", "").statusCode());
    assertEquals(401, send(null, "GET", "/missing/events/stream", "").statusCode());
    assertEquals(404, send("approver", "GET", "/run-missing/history", "").statusCode());
    assertEquals(400, send("operator", "GET", "?limit=501", "").statusCode());
  }

  @Test
  @Timeout(60)
  void activeStreamDisconnectReleasesCapacityAndCursorCanResume() throws Exception {
    String id = UUID.randomUUID().toString();
    String run = "run-" + id;
    assertEquals(202, send("operator", "POST", "", """
        {"requestId":"%s","service":"orders","approvalTimeoutSeconds":300}
        """.formatted(id)).statusCode());
    await(run, "WAITING_APPROVAL");
    var connections = new java.util.ArrayList<java.io.InputStream>();
    String cursor;
    try {
      for (int n = 0; n < 32; n++) {
        var connection = http.send(request("operator", "GET", "/" + run + "/events/stream", "").build(),
            HttpResponse.BodyHandlers.ofInputStream());
        connections.add(connection.body());
        assertEquals(200, connection.statusCode());
      }
      assertEquals(429, send("operator", "GET", "/" + run + "/events/stream", "").statusCode());
      var reader = new java.io.BufferedReader(new java.io.InputStreamReader(connections.getFirst(), StandardCharsets.UTF_8));
      String line;
      do { line = reader.readLine(); } while (line != null && !line.startsWith("id:"));
      assertNotNull(line);
      cursor = line.substring(3).trim();
    } finally {
      for (var connection : connections) connection.close();
    }
    boolean admitted = false;
    for (int n = 0; n < 40; n++) {
      var connection = http.send(request("operator", "GET", "/" + run + "/events/stream", "").build(),
          HttpResponse.BodyHandlers.ofInputStream());
      connection.body().close();
      if (connection.statusCode() == 200) { admitted = true; break; }
      assertEquals(429, connection.statusCode());
      Thread.sleep(100);
    }
    assertTrue(admitted, "Client disconnect must release stream capacity before the 25-second reconnect deadline");
    assertEquals(202, send("operator", "POST", "/" + run + "/cancel", "").statusCode());
    await(run, "CANCELLED");
    var resumed = http.send(request("approver", "GET", "/" + run + "/events/stream", "")
        .header("Last-Event-ID", cursor).build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, resumed.statusCode());
    var ids = resumed.body().lines().filter(value -> value.startsWith("id:")).map(value -> value.substring(3).trim()).toList();
    long after = Long.parseLong(cursor.substring(cursor.lastIndexOf(':') + 1));
    assertFalse(ids.isEmpty());
    for (int n = 0; n < ids.size(); n++) assertEquals(cursor.substring(0, cursor.lastIndexOf(':') + 1) + (after + n + 1), ids.get(n));
  }

  @Test
  void damagedCachedProjectionReturnsSanitizedUnavailableAndCanBeRebuilt() throws Exception {
    String id = UUID.randomUUID().toString();
    String run = "run-" + id;
    assertEquals(202, send("operator", "POST", "", """
        {"requestId":"%s","service":"orders","approvalTimeoutSeconds":300}
        """.formatted(id)).statusCode());
    await(run, "WAITING_APPROVAL");
    assertEquals(200, send("operator", "GET", "/" + run + "/history", "").statusCode());
    jdbc.update("UPDATE platform_run_views SET view_json=? WHERE run_id=?", "damaged-projection-marker", run);
    var failed = send("operator", "GET", "/" + run + "/history?refresh=false", "");
    assertEquals(503, failed.statusCode());
    assertFalse(failed.body().contains("damaged-projection-marker"));
    assertEquals(200, send("operator", "GET", "/" + run + "/history", "").statusCode());
    assertEquals(202, send("operator", "POST", "/" + run + "/cancel", "").statusCode());
    await(run, "CANCELLED");
  }

  private void await(String run, String state) throws Exception {
    for (int n = 0; n < 200; n++) {
      if (send("operator", "GET", "/" + run, "").body().contains("\"state\":\"" + state + "\"")) return;
      Thread.sleep(25);
    }
    fail("Run did not reach " + state);
  }

  private HttpResponse<String> send(String actor, String method, String path, String body) throws Exception {
    return http.send(request(actor, method, path, body).build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder request(String actor, String method, String path, String body) {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/runs" + path))
        .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json").header("X-Platform-Request", "true")
        .method(method, HttpRequest.BodyPublishers.ofString(body));
    if (actor != null) request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
        (actor + ":test-" + actor + "-password").getBytes(StandardCharsets.UTF_8)));
    return request;
  }
}
