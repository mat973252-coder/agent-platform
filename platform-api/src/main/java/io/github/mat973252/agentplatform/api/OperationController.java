package io.github.mat973252.agentplatform.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/runs/{runId}")
class OperationController {
  private final OperationService operations;

  OperationController(OperationService operations) { this.operations = operations; }

  @GetMapping("/operation")
  OperationService.OperationView operation(@PathVariable String runId) { return operations.view(runId); }

  @PostMapping("/reconciliation")
  ResponseEntity<RunController.Submitted> reconcile(@PathVariable String runId, @Valid @RequestBody Request request, Principal actor) {
    if (request.action() == Action.CHECK) {
      operations.requestCheck(runId);
    } else {
      if (request.reason() == null || request.reason().isBlank()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Closing an unknown outcome requires a reason");
      }
      operations.closeUnknown(runId, actor.getName(), request.reason());
    }
    return ResponseEntity.accepted().body(new RunController.Submitted("RECONCILIATION_SUBMITTED"));
  }

  enum Action { CHECK, CLOSE }
  record Request(@NotNull Action action, @Size(max = 512) String reason) {}
}
