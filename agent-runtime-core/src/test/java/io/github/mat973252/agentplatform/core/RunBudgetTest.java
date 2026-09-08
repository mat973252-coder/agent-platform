package io.github.mat973252.agentplatform.core;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RunBudgetTest {
  @Test
  void limitsMustBePositiveAndBounded() {
    assertEquals(6, RunBudget.defaults().maxModelSteps());
    assertThrows(IllegalArgumentException.class, () -> new RunBudget(0, 900, 100, 100));
    assertThrows(IllegalArgumentException.class, () -> new RunBudget(6, 0, 100, 100));
    assertThrows(IllegalArgumentException.class, () -> new RunBudget(6, 900, -1, 100));
    assertThrows(IllegalArgumentException.class, () -> new RunBudget(6, 900, 100, 0));
    assertThrows(IllegalArgumentException.class, () -> new RunBudget(101, 900, 100, 100));
  }

  @Test
  void usageCannotBeNegativeOrOverflowItsTokenTotal() {
    assertEquals(15, new ModelUsage(10, 5, 20).tokens());
    assertThrows(IllegalArgumentException.class, () -> new ModelUsage(-1, 5, 20));
    assertThrows(IllegalArgumentException.class, () -> new ModelUsage(10, 5, -1));
    assertThrows(IllegalArgumentException.class, () -> new ModelUsage(Long.MAX_VALUE, 1, 20));
  }
}
