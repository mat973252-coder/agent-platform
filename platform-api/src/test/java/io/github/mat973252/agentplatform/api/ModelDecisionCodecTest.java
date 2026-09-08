package io.github.mat973252.agentplatform.api;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ModelDecisionCodecTest {
  private final ModelDecisionCodec codec = new ModelDecisionCodec();
  private final String valid = """
      {"schemaVersion":"1","action":"CALL_TOOL","tool":"ops.restart",
       "arguments":{"service":"orders"},"message":"Use the approved restart"}
      """;

  @Test
  void strictJsonProducesAValidatedDecision() {
    assertEquals("ops.restart", codec.decode(valid, "orders").tool());
  }

  @Test
  void malformedOrOverpermissiveModelOutputIsRejected() {
    for (String input : new String[] {"not json", valid + valid,
        valid.replace("\"service\":\"orders\"", "\"service\":123"),
        valid.replace("\"service\":\"orders\"", "\"service\":\"orders\",\"approved\":\"true\""),
        valid.replace("\"action\":\"CALL_TOOL\"", "\"action\":\"FINISH\",\"action\":\"CALL_TOOL\""),
        valid.replace("\"schemaVersion\":\"1\",", ""),
        valid.replace("\"message\":", "\"approvalId\":\"forged\",\"message\":"),
        valid.replace("ops.restart", "shell.exec"), valid.replace("orders", "payments")}) {
      assertThrows(IllegalArgumentException.class, () -> codec.decode(input, "orders"), input);
    }
  }
}
