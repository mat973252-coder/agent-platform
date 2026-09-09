package io.github.mat973252.agentplatform.api;

import io.temporal.client.WorkflowClient;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

@Configuration(proxyBeanMethods = false)
class ObservationConfiguration {
  @Bean
  @DependsOn("flywayInitializer")
  JdbcRunProjectionStore runProjectionStore(DataSource source) { return new JdbcRunProjectionStore(source); }

  @Bean
  RunObservationService runObservationService(WorkflowClient client, JdbcRunProjectionStore store) {
    return new RunObservationService(client, store, Clock.systemUTC());
  }
}
