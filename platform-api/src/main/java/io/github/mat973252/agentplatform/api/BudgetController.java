package io.github.mat973252.agentplatform.api;

import io.github.mat973252.agentplatform.core.BudgetSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class BudgetController {
  private final RunService runs;
  private final JdbcModelBudgetStore budgets;

  BudgetController(RunService runs, JdbcModelBudgetStore budgets) { this.runs = runs; this.budgets = budgets; }

  @GetMapping("/api/runs/{runId}/budget")
  BudgetSnapshot budget(@PathVariable String runId) {
    runs.snapshot(runId);
    var budget = budgets.get(runId);
    if (budget == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No budget record for this Run");
    return budget;
  }
}
