package io.github.mat973252.agentplatform.api;

import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowServiceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiErrors {
  @ExceptionHandler(WorkflowExecutionAlreadyStarted.class)
  ProblemDetail duplicateRun() {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Request ID has already been used");
  }

  @ExceptionHandler(WorkflowNotFoundException.class)
  ProblemDetail missingRun() {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Run was not found");
  }

  @ExceptionHandler(IllegalStateException.class)
  ProblemDetail invalidState(IllegalStateException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
  }

  @ExceptionHandler(WorkflowServiceException.class)
  ProblemDetail temporalUnavailable() {
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Workflow service is unavailable");
  }
}
