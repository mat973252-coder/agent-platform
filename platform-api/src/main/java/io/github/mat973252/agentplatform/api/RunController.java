package io.github.mat973252.agentplatform.api;

import io.github.mat973252.agentplatform.core.ApprovalCommand;
import io.github.mat973252.agentplatform.core.ApprovalDecision;
import io.github.mat973252.agentplatform.core.RunRequest;
import io.github.mat973252.agentplatform.core.RunSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import io.github.mat973252.agentplatform.permit.ApprovalRecord;
import io.github.mat973252.agentplatform.permit.JdbcApprovalStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/runs")
class RunController {
  private final RunService runs;
  private final JdbcApprovalStore approvals;

  RunController(RunService runs, JdbcApprovalStore approvals) {
    this.runs = runs;
    this.approvals = approvals;
  }

  @PostMapping
  ResponseEntity<CreatedRun> create(@Valid @RequestBody CreateRun request) {
    String id = runs.create(request.requestId(),
        new RunRequest(request.service(), request.approvalTimeoutSeconds()));
    var body = new CreatedRun(id, "/api/runs/" + id);
    return ResponseEntity.accepted().location(URI.create(body.statusUrl())).body(body);
  }

  @GetMapping("/{runId}")
  RunSnapshot snapshot(@PathVariable String runId) {
    return runs.snapshot(runId);
  }

  @PostMapping("/{runId}/approval")
  ResponseEntity<Submitted> approve(@PathVariable String runId, @Valid @RequestBody DecideRun request, Principal principal) {
    runs.submitApproval(runId, new ApprovalCommand(request.approvalId(), request.decision()), principal.getName());
    return ResponseEntity.accepted().body(new Submitted("APPROVAL_SUBMITTED"));
  }

  @PostMapping("/{runId}/cancel")
  ResponseEntity<Submitted> cancel(@PathVariable String runId, Principal principal) {
    runs.cancel(runId, principal.getName());
    return ResponseEntity.accepted().body(new Submitted("CANCELLATION_SUBMITTED"));
  }

  @GetMapping("/{runId}/approval")
  ApprovalRecord approval(@PathVariable String runId) {
    runs.snapshot(runId);
    var record = approvals.findByRun(runId);
    if (record == null) throw new IllegalStateException("Run has no persistent approval request");
    return record;
  }

  @GetMapping("/pending-approvals")
  List<ApprovalRecord> pendingApprovals() { return approvals.pending(); }

  record CreateRun(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}") String requestId,
      @NotNull @Pattern(regexp = "orders") String service,
      @Min(1) @Max(3600) int approvalTimeoutSeconds) {}

  record DecideRun(@NotBlank String approvalId, @NotNull ApprovalDecision decision) {}

  record CreatedRun(String runId, String statusUrl) {}

  record Submitted(String status) {}
}
