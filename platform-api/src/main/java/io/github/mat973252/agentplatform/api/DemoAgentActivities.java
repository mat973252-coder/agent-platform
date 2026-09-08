package io.github.mat973252.agentplatform.api;

import io.github.mat973252.agentplatform.core.AgentContext;
import io.github.mat973252.agentplatform.core.AgentDecision;
import io.github.mat973252.agentplatform.core.VerificationResult;
import io.github.mat973252.agentplatform.durable.AgentActivities;
import io.github.mat973252.agentplatform.permit.JdbcApprovalStore;
import io.github.mat973252.agentplatform.permit.JdbcRestartLedger;
import io.temporal.failure.ApplicationFailure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
class DemoAgentActivities implements AgentActivities {
  private final JdbcApprovalStore approvals;
  private final JdbcRestartLedger ledger;
  private final ModelDecisionCodec codec = new ModelDecisionCodec();

  DemoAgentActivities(JdbcApprovalStore approvals, JdbcRestartLedger ledger) {
    this.approvals = approvals;
    this.ledger = ledger;
  }

  @Override
  public String readRunbook(String service) {
    requireOrders(service);
    return resource("orders-runbook-v1.md");
  }

  @Override
  public AgentDecision plan(AgentContext context) {
    try {
      return codec.decode(response(context), context.service());
    } catch (IllegalArgumentException invalid) {
      throw ApplicationFailure.newNonRetryableFailure("Model decision rejected", "MODEL_OUTPUT_INVALID");
    }
  }

  String response(AgentContext context) {
    requireOrders(context.service());
    if (!AgentContext.MODEL_VERSION.equals(context.modelVersion())
        || !AgentContext.PROMPT_VERSION.equals(context.promptVersion())
        || !AgentContext.TOOL_VERSION.equals(context.toolVersion())
        || !AgentContext.RUNBOOK_VERSION.equals(context.runbookVersion())) {
      throw ApplicationFailure.newNonRetryableFailure("Unsupported agent version", "AGENT_VERSION_UNSUPPORTED");
    }
    String fixture = context.verified() ? "finish" : context.writeCompleted() ? "verify" : "restart";
    return resource(fixture + "-v1.json");
  }

  @Override
  public VerificationResult verifyOperation(String operationId, String service, String approvalId, String expectedOutput) {
    requireOrders(service);
    var approval = approvals.get(approvalId);
    var receipt = ledger.find(operationId);
    boolean confirmed = approval.operationId().equals(operationId) && receipt != null
        && receipt.service().equals(service) && receipt.fingerprint().equals(approval.fingerprint())
        && receipt.output().equals(expectedOutput);
    return new VerificationResult(confirmed, confirmed
        ? "TEST_LEDGER_CONFIRMED: matching committed receipt; real service health is not measured"
        : "TEST_LEDGER_NOT_CONFIRMED");
  }

  private void requireOrders(String service) {
    if (!"orders".equals(service)) throw ApplicationFailure.newNonRetryableFailure("Unsupported service", "INVALID_RESOURCE");
  }

  private String resource(String name) {
    try (var stream = new ClassPathResource("agent/" + name).getInputStream()) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException missing) { throw new IllegalStateException("Offline agent resource unavailable", missing); }
  }
}
