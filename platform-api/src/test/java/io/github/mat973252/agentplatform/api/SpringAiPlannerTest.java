package io.github.mat973252.agentplatform.api;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import io.github.mat973252.agentplatform.core.*;
import io.temporal.failure.ApplicationFailure;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SpringAiPlannerTest {
  private HttpServer server;
  private ModelProfile model;
  private SpringAiPlanner planner;
  private final AtomicInteger calls = new AtomicInteger();
  private String request;
  private int status = 200;
  private boolean dropResponse;
  private String response;
  private final AgentContext context = new AgentContext("run-http:model:1", "orders", "synthetic evidence",
      "synthetic runbook", null, false, false, "test-model", "diagnostics-prompt-v2",
      AgentContext.TOOL_VERSION, AgentContext.RUNBOOK_VERSION);

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      calls.incrementAndGet();
      request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      if (dropResponse) { exchange.close(); return; }
      byte[] body = response.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    model = new ModelProfile("OPENAI_COMPATIBLE", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
        "test-model", "diagnostics-prompt-v2", "test-price-v1", 8192, 512, 3_000_000, 15_000_000);
    planner = new SpringAiPlanner(model.baseUrl(), "synthetic-test-key");
    response = """
        {"id":"test-response","object":"chat.completion","created":1,"model":"test-model",
         "choices":[{"index":0,"message":{"role":"assistant","content":"test output"},"finish_reason":"stop"}],
         "usage":{"prompt_tokens":1000,"completion_tokens":100,"total_tokens":1100}}
        """;
  }

  @AfterEach void close() { server.stop(0); }

  @Test
  void sendsBoundedSingleRequestAndUsesProviderUsage() {
    var result = planner.call(model, context, Duration.ofSeconds(3));
    assertEquals("test output", result.text());
    assertEquals(new ModelUsage(1000, 100, 4500), result.usage());
    var json = JsonMapper.builder().build().readTree(request);
    assertEquals("test-model", json.path("model").asString());
    assertEquals(512, json.path("max_tokens").asInt());
    assertFalse(json.has("tools"));
    assertEquals("system", json.path("messages").get(0).path("role").asString());
    assertEquals("user", json.path("messages").get(1).path("role").asString());
    assertEquals(1, calls.get());
  }

  @Test
  void serverFailureDoesNotTriggerHiddenSdkRetriesOrExposeResponseBody() {
    status = 500;
    response = "{\"error\":{\"message\":\"sensitive-upstream-detail\",\"type\":\"server_error\"}}";
    var failure = assertThrows(ApplicationFailure.class, () -> planner.call(model, context, Duration.ofSeconds(3)));
    assertEquals(1, calls.get());
    assertFalse(failure.toString().contains("sensitive-upstream-detail"));
    assertNull(failure.getCause());
  }

  @Test
  void connectionLossAfterRequestIsNotRetriedByTheTransport() {
    dropResponse = true;
    assertThrows(ApplicationFailure.class, () -> planner.call(model, context, Duration.ofSeconds(2)));
    assertEquals(1, calls.get());
  }

  @Test
  void missingUsageCannotBecomeZeroCostAndEndpointDriftCannotReceiveTheKey() {
    var json = JsonMapper.builder().build().readTree(response);
    ((tools.jackson.databind.node.ObjectNode) json).remove("usage");
    response = json.toString();
    var failure = assertThrows(ApplicationFailure.class, () -> planner.call(model, context, Duration.ofSeconds(3)));
    assertEquals("MODEL_USAGE_UNKNOWN", failure.getType());
    assertTrue(failure.isNonRetryable());
    assertThrows(ApplicationFailure.class, () -> new SpringAiPlanner("https://different.example/v1", "synthetic-test-key")
        .call(model, context, Duration.ofSeconds(3)));
    assertEquals(1, calls.get());
  }
}
