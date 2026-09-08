package io.github.mat973252.agentplatform.api;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"platform.temporal.external=false", "logging.level.io.temporal=ERROR",
        "spring.datasource.url=jdbc:h2:mem:budget-api-tests;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa",
        "spring.datasource.password=", "platform.security.operator-password=test-operator-password",
        "platform.security.approver-password=test-approver-password", "platform.budget.max-tokens=2000"})
@Import(RunApiTest.TemporalTestConfiguration.class)
@Timeout(20)
class BudgetApiTest {
  @LocalServerPort private int port;
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void callerCannotOverrideServerBudgetAndExhaustionPreventsToolExecution() throws Exception {
    String id = UUID.randomUUID().toString();
    String path = "/api/runs/run-" + id;
    assertEquals(202, send("POST", "/api/runs", """
        {"requestId":"%s","service":"orders","approvalTimeoutSeconds":300,
         "budget":{"maxTokens":999999,"maxCostMicrousd":999999}}
        """.formatted(id)).statusCode());
    String state = "";
    for (int attempt = 0; attempt < 200; attempt++) {
      state = send("GET", path, "").body();
      if (state.contains("\"state\":\"FAILED\"")) break;
      Thread.sleep(25);
    }
    assertTrue(state.contains("\"state\":\"FAILED\""), state);
    assertTrue(state.contains("TOKEN_BUDGET_EXCEEDED"), state);
    var budget = send("GET", path + "/budget", "");
    assertEquals(200, budget.statusCode());
    assertTrue(budget.body().contains("\"maxTokens\":2000"), budget.body());
    assertTrue(budget.body().contains("\"modelAttempts\":0"), budget.body());
    assertTrue(budget.body().contains("\"usedTokens\":0"), budget.body());
    assertTrue(budget.body().contains("\"reservedTokens\":0"), budget.body());
    assertEquals(409, send("GET", path + "/approval", "").statusCode());
    assertEquals(409, send("GET", path + "/operation", "").statusCode());
  }

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
        .header("X-Platform-Request", "true")
        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
            "operator:test-operator-password".getBytes(StandardCharsets.UTF_8)))
        .method(method, HttpRequest.BodyPublishers.ofString(body)).build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
