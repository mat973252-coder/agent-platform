package io.github.mat973252.agentplatform.api;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import io.github.mat973252.agentplatform.durable.DiagnosticsWorkflowImpl;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"platform.temporal.external=false", "logging.level.io.temporal=ERROR",
        "spring.datasource.url=jdbc:h2:mem:remote-api-tests;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa",
        "spring.datasource.password=", "platform.security.operator-password=test-operator-password",
        "platform.security.approver-password=test-approver-password", "platform.approval-delivery.interval-ms=100",
        "platform.budget.max-model-steps=4", "platform.budget.max-duration-seconds=120", "platform.budget.max-cost-microusd=500000"})
@Import(RunApiTest.TemporalTestConfiguration.class)
@Timeout(150)
class SpringAiRunApiTest {
  private static final boolean LIVE = "true".equals(System.getenv("PLATFORM_LIVE_MODEL_TEST"));
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static HttpServer server;
  @LocalServerPort private int port;
  @Autowired private TestWorkflowEnvironment environment;
  private final HttpClient http = HttpClient.newHttpClient();

  @DynamicPropertySource
  static void modelProperties(DynamicPropertyRegistry registry) throws Exception {
    String endpoint;
    if (LIVE) endpoint = required("PLATFORM_LIVE_MODEL_BASE_URL");
    else {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/v1/chat/completions", exchange -> {
        var request = JSON.readTree(exchange.getRequestBody().readAllBytes());
        var context = JSON.readTree(request.path("messages").get(1).path("content").asString());
        boolean finished = context.path("verified").asBoolean();
        String tool = context.path("writeCompleted").asBoolean() ? "ops.verify" : "ops.restart";
        String decision = finished
            ? "{\"schemaVersion\":\"1\",\"action\":\"FINISH\",\"tool\":null,\"arguments\":{},\"message\":\"Only test ledger verified\"}"
            : "{\"schemaVersion\":\"1\",\"action\":\"CALL_TOOL\",\"tool\":\"" + tool + "\",\"arguments\":{\"service\":\"orders\"},\"message\":\"Follow runbook\"}";
        byte[] response = JSON.writeValueAsBytes(Map.of("id", "test-response", "object", "chat.completion", "created", 1,
            "model", "test-model", "choices", new Object[]{Map.of("index", 0, "message", Map.of("role", "assistant", "content", decision),
                "finish_reason", "stop")}, "usage", Map.of("prompt_tokens", 1000, "completion_tokens", 100, "total_tokens", 1100)));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
      });
      server.start();
      endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }
    registry.add("platform.model.provider", () -> "OPENAI_COMPATIBLE");
    registry.add("platform.model.base-url", () -> endpoint);
    registry.add("platform.model.name", () -> LIVE ? required("PLATFORM_LIVE_MODEL_NAME") : "test-model");
    registry.add("platform.model.api-key", () -> LIVE ? required("PLATFORM_LIVE_MODEL_API_KEY") : "synthetic-test-key");
    registry.add("platform.model.pricing-version", () -> LIVE ? required("PLATFORM_LIVE_MODEL_PRICING_VERSION") : "test-price-v1");
    registry.add("platform.model.input-microusd-per-million", () -> LIVE ? required("PLATFORM_LIVE_MODEL_INPUT_PRICE") : "3000000");
    registry.add("platform.model.output-microusd-per-million", () -> LIVE ? required("PLATFORM_LIVE_MODEL_OUTPUT_PRICE") : "15000000");
  }

  @AfterAll static void stopModel() { if (server != null) server.stop(0); }

  @Test
  void springAiDecisionPassesApprovalLedgerVerificationAndBudgetSettlement() throws Exception {
    String id = UUID.randomUUID().toString();
    String path = "/api/runs/run-" + id;
    send("operator", "POST", "/api/runs", "{\"requestId\":\"" + id + "\",\"service\":\"orders\",\"approvalTimeoutSeconds\":90}", 202);
    var pending = awaitState(path, "WAITING_APPROVAL");
    assertEquals("diagnostics-prompt-v2", pending.path("agent").path("promptVersion").asString());
    send("approver", "POST", path + "/approval",
        "{\"approvalId\":\"" + pending.path("approvalId").asString() + "\",\"decision\":\"APPROVE\"}", 202);
    var finished = awaitState(path, "SUCCEEDED");
    assertTrue(finished.path("output").asString().startsWith("TEST_LEDGER_RESTART:orders"));
    assertTrue(finished.path("agent").path("observation").asString().startsWith("VERIFICATION_CONFIRMED"));
    var budget = send("operator", "GET", path + "/budget", "", 200);
    assertEquals("PROVIDER_USAGE_CONFIGURED_ESTIMATE", budget.path("meteringMode").asString());
    assertTrue(budget.path("usedTokens").asLong() > 0);
    assertTrue(budget.path("usedCostMicrousd").asLong() > 0);
    assertEquals(0, budget.path("reservedTokens").asLong());
    if (!LIVE) {
      assertEquals(3300, budget.path("usedTokens").asLong());
      assertEquals(13500, budget.path("usedCostMicrousd").asLong());
    }
    WorkflowReplayer.replayWorkflowExecution(environment.getWorkflowClient().fetchHistory("run-" + id), DiagnosticsWorkflowImpl.class);
    System.out.println("MODEL_ACCEPTANCE " + JSON.writeValueAsString(Map.of("live", LIVE, "runId", "run-" + id,
        "model", budget.path("model").path("model").asString(), "usedTokens", budget.path("usedTokens").asLong(),
        "estimatedCostMicrousd", budget.path("usedCostMicrousd").asLong(), "attempts", budget.path("modelAttempts").asLong())));
  }

  private JsonNode awaitState(String path, String state) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(110).toNanos();
    while (System.nanoTime() < deadline) {
      var value = send("operator", "GET", path, "", 200);
      if (value.path("state").asString().equals(state)) return value;
      if (value.path("state").asString().matches("FAILED|TIMED_OUT|DENIED|REJECTED")) {
        fail("Run ended: " + value.path("state").asString() + " / " + value.path("reasonCode").asString());
      }
      Thread.sleep(100);
    }
    throw new AssertionError("Run did not reach " + state);
  }

  private JsonNode send(String actor, String method, String path, String body, int expected) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10))
        .header("Content-Type", "application/json").header("X-Platform-Request", "true")
        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((actor + ":test-" + actor + "-password").getBytes(StandardCharsets.UTF_8)))
        .method(method, HttpRequest.BodyPublishers.ofString(body)).build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(expected, response.statusCode());
    return JSON.readTree(response.body());
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
    return value;
  }
}
