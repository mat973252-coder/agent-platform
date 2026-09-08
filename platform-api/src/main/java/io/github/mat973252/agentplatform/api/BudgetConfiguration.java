package io.github.mat973252.agentplatform.api;

import io.github.mat973252.agentplatform.core.RunBudget;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

@Configuration(proxyBeanMethods = false)
class BudgetConfiguration {
  @Bean
  @DependsOn("flywayInitializer")
  JdbcModelBudgetStore modelBudgets(DataSource source) { return new JdbcModelBudgetStore(source, Clock.systemUTC()); }

  @Bean
  RunBudget runBudget(@Value("${platform.budget.max-model-steps}") int steps,
      @Value("${platform.budget.max-duration-seconds}") int seconds,
      @Value("${platform.budget.max-tokens}") long tokens,
      @Value("${platform.budget.max-cost-microusd}") long cost) {
    return new RunBudget(steps, seconds, tokens, cost);
  }
}
