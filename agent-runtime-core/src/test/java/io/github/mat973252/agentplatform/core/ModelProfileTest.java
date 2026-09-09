package io.github.mat973252.agentplatform.core;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ModelProfileTest {
  @Test
  void legacyInputsRemainOfflineAndPricesUseIntegerMicrousd() {
    assertEquals(ModelProfile.offline(), new RunRequest("orders", 30).model());
    var model = new ModelProfile("OPENAI_COMPATIBLE", "https://example.com/v1", "test-model",
        "diagnostics-prompt-v2", "test-price-v1", 8192, 512, 3_000_000, 15_000_000);
    assertEquals(4500, model.usage(1000, 100).costMicrousd());
    assertEquals(1, model.usage(1, 0).inputTokens());
    assertEquals(model, new RunRequest("orders", 30, RunBudget.defaults(), model).model());
  }

  @Test
  void rejectsCredentialsInEndpointsAndMissingPrices() {
    assertThrows(IllegalArgumentException.class, () -> new ModelProfile("OPENAI_COMPATIBLE",
        "https://secret@example.com/v1", "test", "diagnostics-prompt-v2", "v1", 8192, 512, 3, 15));
    assertThrows(IllegalArgumentException.class, () -> new ModelProfile("OPENAI_COMPATIBLE",
        "https://example.com/v1?key=secret", "test", "diagnostics-prompt-v2", "v1", 8192, 512, 3, 15));
    assertThrows(IllegalArgumentException.class, () -> new ModelProfile("OPENAI_COMPATIBLE",
        "https://example.com/v1", "test", "diagnostics-prompt-v2", "", 8192, 512, 0, 0));
  }
}
