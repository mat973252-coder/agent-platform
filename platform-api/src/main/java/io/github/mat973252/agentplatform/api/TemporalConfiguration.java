package io.github.mat973252.agentplatform.api;

import io.github.mat973252.agentplatform.durable.DiagnosticsWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TemporalConfiguration {
  @Bean(initMethod = "start", destroyMethod = "shutdown")
  WorkerFactory workerFactory(WorkflowClient client, DemoOperations activities, DemoAgentActivities agentActivities,
      @Value("${platform.temporal.task-queue}") String taskQueue) {
    var factory = WorkerFactory.newInstance(client);
    var worker = factory.newWorker(taskQueue);
    worker.registerWorkflowImplementationTypes(DiagnosticsWorkflowImpl.class);
    worker.registerActivitiesImplementations(activities, agentActivities);
    return factory;
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(name = "platform.temporal.external", havingValue = "true", matchIfMissing = true)
  static class ExternalConnection {
    @Bean(destroyMethod = "shutdown")
    WorkflowServiceStubs workflowServiceStubs(@Value("${platform.temporal.target}") String target) {
      return WorkflowServiceStubs.newServiceStubs(
          WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
    }

    @Bean
    WorkflowClient workflowClient(WorkflowServiceStubs stubs,
        @Value("${platform.temporal.namespace}") String namespace) {
      return WorkflowClient.newInstance(stubs,
          WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
    }
  }
}
