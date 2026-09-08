package io.github.mat973252.agentplatform.api;

import io.github.mat973252.agentplatform.permit.JdbcApprovalStore;
import io.github.mat973252.agentplatform.permit.PersistentGovernance;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

@Configuration(proxyBeanMethods = false)
class ApprovalConfiguration {
  @Bean
  @DependsOn("flywayInitializer")
  JdbcApprovalStore approvalStore(DataSource source) {
    return new JdbcApprovalStore(source, Clock.systemUTC());
  }

  @Bean
  PersistentGovernance persistentGovernance(JdbcApprovalStore store,
      @Value("${platform.security.approver-username:approver}") String approver,
      @Value("${platform.policy.restart-enabled:true}") boolean restartEnabled) {
    return new PersistentGovernance(store, approver, restartEnabled);
  }
}
