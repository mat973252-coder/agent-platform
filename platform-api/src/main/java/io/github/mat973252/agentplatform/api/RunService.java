package io.github.mat973252.agentplatform.api;

import io.github.mat973252.agentplatform.core.ApprovalCommand;
import io.github.mat973252.agentplatform.core.RunRequest;
import io.github.mat973252.agentplatform.core.RunSnapshot;
import io.github.mat973252.agentplatform.core.RunState;
import io.github.mat973252.agentplatform.durable.DiagnosticsWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class RunService {
  private final WorkflowClient client;
  private final String taskQueue;

  RunService(WorkflowClient client, @Value("${platform.temporal.task-queue}") String taskQueue) {
    this.client = client;
    this.taskQueue = taskQueue;
  }

  String create(String requestId, RunRequest request) {
    String runId = "run-" + requestId;
    var workflow = client.newWorkflowStub(DiagnosticsWorkflow.class,
        WorkflowOptions.newBuilder()
            .setWorkflowId(runId)
            .setTaskQueue(taskQueue)
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .build());
    WorkflowClient.start(workflow::execute, request);
    return runId;
  }

  RunSnapshot snapshot(String runId) {
    return workflow(runId).snapshot();
  }

  void submitApproval(String runId, ApprovalCommand command) {
    var workflow = workflow(runId);
    var snapshot = workflow.snapshot();
    if (snapshot.state() != RunState.WAITING_APPROVAL
        || !command.approvalId().equals(snapshot.approvalId())) {
      throw new IllegalStateException("Run is not waiting for this approval");
    }
    workflow.submitApproval(command);
  }

  void cancel(String runId) {
    var workflow = workflow(runId);
    if (workflow.snapshot().state().terminal()) {
      throw new IllegalStateException("Run is already terminal");
    }
    WorkflowStub.fromTyped(workflow).cancel();
  }

  private DiagnosticsWorkflow workflow(String runId) {
    return client.newWorkflowStub(DiagnosticsWorkflow.class, runId);
  }
}
