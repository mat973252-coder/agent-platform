package io.github.mat973252.agentplatform.api;

import io.github.mat973252.agentplatform.core.ApprovalCommand;
import io.github.mat973252.agentplatform.core.RunRequest;
import io.github.mat973252.agentplatform.core.RunBudget;
import io.github.mat973252.agentplatform.core.ModelProfile;
import io.github.mat973252.agentplatform.core.RunSnapshot;
import io.github.mat973252.agentplatform.core.RunState;
import io.github.mat973252.agentplatform.durable.DiagnosticsWorkflow;
import io.github.mat973252.agentplatform.permit.JdbcApprovalStore;
import io.github.mat973252.agentplatform.permit.PersistentGovernance;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;

@Service
class RunService {
  private final WorkflowClient client;
  private final String taskQueue;
  private final JdbcApprovalStore approvals;
  private final PersistentGovernance governance;
  private final RunBudget budget;
  private final ModelProfile model;

  RunService(WorkflowClient client, @Value("${platform.temporal.task-queue}") String taskQueue,
      JdbcApprovalStore approvals, PersistentGovernance governance, RunBudget budget, ModelProfile model) {
    this.client = client;
    this.taskQueue = taskQueue;
    this.approvals = approvals;
    this.governance = governance;
    this.budget = budget;
    this.model = model;
  }

  String create(String requestId, RunRequest request) {
    String runId = "run-" + requestId;
    var workflow = client.newWorkflowStub(DiagnosticsWorkflow.class,
        WorkflowOptions.newBuilder()
            .setWorkflowId(runId)
            .setTaskQueue(taskQueue)
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .build());
    WorkflowClient.start(workflow::execute, new RunRequest(request.service(), request.approvalTimeoutSeconds(), budget, model));
    return runId;
  }

  RunSnapshot snapshot(String runId) {
    return workflow(runId).snapshot();
  }

  void submitApproval(String runId, ApprovalCommand command, String actor) {
    var record = approvals.get(command.approvalId());
    if (!record.runId().equals(runId)) throw new IllegalStateException("Approval does not belong to this Run");
    if (!governance.canApprove(actor, record.resourceId())) throw new AccessDeniedException("Approval permission denied");
    if (record.status().equals(command.decision().name()) && actor.equals(record.decidedBy())) {
      approvals.decide(runId, command.approvalId(), command.decision(), actor);
      return;
    }
    var workflow = workflow(runId);
    var snapshot = workflow.snapshot();
    if (snapshot.state() != RunState.WAITING_APPROVAL
        || !command.approvalId().equals(snapshot.approvalId())) {
      throw new IllegalStateException("Run is not waiting for this approval");
    }
    approvals.decide(runId, command.approvalId(), command.decision(), actor);
  }

  void cancel(String runId, String actor) {
    var workflow = workflow(runId);
    if (workflow.snapshot().state().terminal()) {
      throw new IllegalStateException("Run is already terminal");
    }
    var record = approvals.findByRun(runId);
    if (record == null) {
      WorkflowStub.fromTyped(workflow).cancel();
    } else {
      approvals.cancel(runId, record.approvalId(), actor);
    }
  }

  private DiagnosticsWorkflow workflow(String runId) {
    return client.newWorkflowStub(DiagnosticsWorkflow.class, runId);
  }
}
