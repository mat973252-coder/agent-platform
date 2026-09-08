package io.github.mat973252.agentplatform.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentDecisionTest {
  @Test
  void onlyDeclaredToolsAndTheRunsServiceCanBeRequested() {
    new AgentDecision("1", "CALL_TOOL", "ops.restart", Map.of("service", "orders"), "Diagnose").validateFor("orders");
    assertThrows(IllegalArgumentException.class, () ->
        new AgentDecision("1", "CALL_TOOL", "shell.exec", Map.of("service", "orders"), "Execute").validateFor("orders"));
    assertThrows(IllegalArgumentException.class, () ->
        new AgentDecision("1", "CALL_TOOL", "ops.restart", Map.of("service", "payments"), "Execute").validateFor("orders"));
    assertThrows(IllegalArgumentException.class, () ->
        new AgentDecision("1", "CALL_TOOL", "ops.restart", Map.of("service", "orders", "approved", "true"), "Execute").validateFor("orders"));
  }

  @Test
  void terminalDecisionsCannotSmuggleAToolAndMessagesAreBounded() {
    new AgentDecision("1", "FINISH", null, Map.of(), "No action needed").validateFor("orders");
    assertThrows(IllegalArgumentException.class, () ->
        new AgentDecision("1", "FINISH", "ops.restart", Map.of(), "Done").validateFor("orders"));
    assertThrows(IllegalArgumentException.class, () ->
        new AgentDecision("2", "FINISH", null, Map.of(), "Done").validateFor("orders"));
    assertThrows(IllegalArgumentException.class, () ->
        new AgentDecision("1", "FINISH", null, Map.of(), "x".repeat(2001)).validateFor("orders"));
  }
}
