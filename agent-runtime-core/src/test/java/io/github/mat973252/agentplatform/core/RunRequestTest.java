package io.github.mat973252.agentplatform.core;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RunRequestTest {
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"production", "http://example.com", " orders "})
  void rejectsTargetsOutsideTheFixedDemo(String service) {
    assertThrows(IllegalArgumentException.class, () -> new RunRequest(service, 60));
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 3601})
  void rejectsUnboundedApprovalWaits(int seconds) {
    assertThrows(IllegalArgumentException.class, () -> new RunRequest("orders", seconds));
  }
}
