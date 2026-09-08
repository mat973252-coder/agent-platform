package io.github.mat973252.agentplatform.durable;

import io.github.mat973252.agentplatform.core.AgentContext;
import io.github.mat973252.agentplatform.core.AgentDecision;
import io.github.mat973252.agentplatform.core.BudgetContext;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface BudgetActivities {
  void initializeBudget(BudgetContext context);
  AgentDecision planWithinBudget(AgentContext context, BudgetContext budget);
}
