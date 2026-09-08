package io.github.mat973252.agentplatform.api;

import static org.junit.jupiter.api.Assertions.*;

import io.temporal.client.WorkflowClient;
import io.temporal.testing.TestWorkflowEnvironment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"platform.temporal.external=false", "logging.level.io.temporal=ERROR",
        "spring.datasource.url=jdbc:h2:mem:api-tests;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa",
        "spring.datasource.password=", "platform.security.operator-password=test-operator-password",
        "platform.security.approver-password=test-approver-password", "platform.approval-delivery.interval-ms=100"})
@Import(RunApiTest.TemporalTestConfiguration.class)
@Timeout(20)
class RunApiTest {
  @LocalServerPort
  private int port;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  @Test
  void unauthenticatedRequestsAreRejected() throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/runs/missing")).build();
    assertEquals(401, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
  }

  @Test
  void createsApprovesAndQueriesARun() throws Exception {
    String requestId = UUID.randomUUID().toString();
    String runId = "run-" + requestId;
    var created = create(requestId, "orders");
    assertEquals(202, created.statusCode());
    assertEquals("/api/runs/" + runId, created.headers().firstValue("Location").orElseThrow());
    assertTrue(awaitState(runId, "WAITING_APPROVAL").body()
        .contains("\"reasonCode\":\"DEMO_RESTART_REQUIRES_APPROVAL\""));
    assertEquals(409, create(requestId, "orders").statusCode());
    var approval = send("POST", "/api/runs/" + runId + "/approval",
        "{\"approvalId\":\"" + runId + ":approval:restart\",\"decision\":\"APPROVE\"}");
    assertEquals(202, approval.statusCode());
    var finished = awaitState(runId, "SUCCEEDED");
    assertTrue(finished.body().contains("SIMULATED_RESTART:orders"));
    assertTrue(finished.body().contains("\"reasonCode\":\"DEMO_RESTART_REQUIRES_APPROVAL\""));
    assertEquals(409, create(requestId, "orders").statusCode(), "Closed IDs cannot be reused");
  }

  @Test
  void rejectionEndsWithoutToolOutput() throws Exception {
    String requestId = UUID.randomUUID().toString();
    String runId = "run-" + requestId;
    assertEquals(202, create(requestId, "orders").statusCode());
    awaitState(runId, "WAITING_APPROVAL");
    assertEquals(202, send("POST", "/api/runs/" + runId + "/approval",
        "{\"approvalId\":\"" + runId + ":approval:restart\",\"decision\":\"REJECT\"}").statusCode());
    assertFalse(awaitState(runId, "REJECTED").body().contains("SIMULATED_RESTART"));
  }

  @Test
  void mismatchedApprovalIsRejectedAndRunCanBeCancelled() throws Exception {
    String requestId = UUID.randomUUID().toString();
    String runId = "run-" + requestId;
    assertEquals(202, create(requestId, "orders").statusCode());
    awaitState(runId, "WAITING_APPROVAL");
    assertEquals(409, send("POST", "/api/runs/" + runId + "/approval",
        "{\"approvalId\":\"wrong\",\"decision\":\"APPROVE\"}").statusCode());
    assertEquals(202, send("POST", "/api/runs/" + runId + "/cancel", "").statusCode());
    awaitState(runId, "CANCELLED");
    assertEquals(409, send("POST", "/api/runs/" + runId + "/cancel", "").statusCode());
  }

  @Test
  void unknownRunsReturnNotFound() throws Exception {
    assertEquals(404, send("GET", "/api/runs/missing-" + UUID.randomUUID(), "").statusCode());
  }

  @Test
  void invalidTargetsIdsAndBudgetsReturnBadRequest() throws Exception {
    assertEquals(400, create("valid-id", "production").statusCode());
    assertEquals(400, create("not a valid id", "orders").statusCode());
    assertEquals(400, send("POST", "/api/runs",
        "{\"requestId\":\"valid-id\",\"service\":\"orders\",\"approvalTimeoutSeconds\":0}").statusCode());
    assertEquals(400, send("POST", "/api/runs", "{}").statusCode());
  }

  @Test
  void anOperatorCannotApproveOrForgeTheApproverIdentity() throws Exception {
    String runId = "run-" + UUID.randomUUID();
    assertEquals(202, create(runId.substring(4), "orders").statusCode());
    awaitState(runId, "WAITING_APPROVAL");
    String body = "{\"approvalId\":\"" + runId + ":approval:restart\",\"decision\":\"APPROVE\",\"actor\":\"approver\"}";
    assertEquals(403, sendAs("operator", "POST", "/api/runs/" + runId + "/approval", body, true).statusCode());
    assertTrue(send("GET", "/api/runs/" + runId + "/approval", "").body().contains("\"status\":\"PENDING\""));
    assertEquals(202, send("POST", "/api/runs/" + runId + "/cancel", "").statusCode());
    awaitState(runId, "CANCELLED");
  }

  @Test
  void mutationsWithoutTheExplicitClientHeaderAreRejected() throws Exception {
    assertEquals(403, sendAs("operator", "POST", "/api/runs", "{}", false).statusCode());
  }

  @Test
  void durableDecisionRecordsTheAuthenticatedActorAndAllowsIdenticalRetries() throws Exception {
    String id = UUID.randomUUID().toString();
    String runId = "run-" + id;
    assertEquals(202, create(id, "orders").statusCode());
    awaitState(runId, "WAITING_APPROVAL");
    String body = "{\"approvalId\":\"" + runId + ":approval:restart\",\"decision\":\"APPROVE\"}";
    assertEquals(202, send("POST", "/api/runs/" + runId + "/approval", body).statusCode());
    awaitState(runId, "SUCCEEDED");
    assertEquals(202, send("POST", "/api/runs/" + runId + "/approval", body).statusCode());
    assertEquals(409, send("POST", "/api/runs/" + runId + "/approval", body.replace("APPROVE", "REJECT")).statusCode());
    var record = send("GET", "/api/runs/" + runId + "/approval", "");
    assertTrue(record.body().contains("\"decidedBy\":\"approver\""));
    assertTrue(record.body().contains("\"toolName\":\"ops.restart\""));
    assertTrue(record.body().contains("\"executionStarted\":true"));
  }

  private HttpResponse<String> create(String requestId, String service) throws Exception {
    return send("POST", "/api/runs", """
        {"requestId":"%s","service":"%s","approvalTimeoutSeconds":300}
        """.formatted(requestId, service));
  }

  private HttpResponse<String> awaitState(String runId, String state) throws Exception {
    for (int attempt = 0; attempt < 200; attempt++) {
      var response = send("GET", "/api/runs/" + runId, "");
      if (response.statusCode() == 200 && response.body().contains("\"state\":\"" + state + "\"")) {
        return response;
      }
      Thread.sleep(25);
    }
    fail("Run did not reach " + state + ": " + send("GET", "/api/runs/" + runId, "").body());
    throw new AssertionError();
  }

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    return sendAs(method.equals("POST") && path.endsWith("/approval") ? "approver" : "operator", method, path, body, true);
  }

  private HttpResponse<String> sendAs(String actor, String method, String path, String body, boolean explicitClient) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(10))
        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
            (actor + ":test-" + actor + "-password").getBytes(StandardCharsets.UTF_8)))
        .header("Content-Type", "application/json")
        .method(method, HttpRequest.BodyPublishers.ofString(body));
    if (explicitClient) request.header("X-Platform-Request", "true");
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TemporalTestConfiguration {
    @Bean(destroyMethod = "close")
    TestWorkflowEnvironment temporalTestEnvironment() {
      return TestWorkflowEnvironment.newInstance();
    }

    @Bean
    WorkflowClient workflowClient(TestWorkflowEnvironment environment) {
      return environment.getWorkflowClient();
    }
  }
}
