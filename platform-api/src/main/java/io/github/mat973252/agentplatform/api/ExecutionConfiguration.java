package io.github.mat973252.agentplatform.api;

import io.github.mat973252.agentplatform.permit.*;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

@Configuration(proxyBeanMethods = false)
class ExecutionConfiguration {
  @Bean
  @DependsOn("flywayInitializer")
  JdbcExecutionStore executionStore(DataSource source) { return new JdbcExecutionStore(source, Clock.systemUTC()); }

  @Bean
  @DependsOn("flywayInitializer")
  JdbcRestartLedger restartLedger(DataSource source) { return new JdbcRestartLedger(source, Clock.systemUTC()); }

  @Bean
  DurableGovernance durableGovernance(PersistentGovernance policy, JdbcExecutionStore executions, JdbcRestartLedger ledger,
      @Value("${platform.demo.failure-mode:NONE}") DemoFailureMode failure) {
    return new DurableGovernance(policy, executions, ledger, failure);
  }
}
