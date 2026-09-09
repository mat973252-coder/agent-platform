package io.github.mat973252.agentplatform.api;

import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.client.okhttp.OkHttpClient;
import com.openai.errors.OpenAIServiceException;
import io.github.mat973252.agentplatform.core.AgentContext;
import io.github.mat973252.agentplatform.core.ModelProfile;
import io.github.mat973252.agentplatform.core.ModelUsage;
import io.temporal.failure.ApplicationFailure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

final class SpringAiPlanner {
  record Reply(String text, ModelUsage usage) {}
  private final String configuredEndpoint;
  private final String apiKey;
  private final String instructions;
  private final JsonMapper json = JsonMapper.builder().build();

  SpringAiPlanner(String configuredEndpoint, String apiKey) {
    this.configuredEndpoint = configuredEndpoint;
    this.apiKey = apiKey;
    try { instructions = new ClassPathResource("agent/diagnostics-prompt-v2.txt").getContentAsString(StandardCharsets.UTF_8); }
    catch (IOException missing) { throw new IllegalStateException("Model prompt unavailable"); }
  }

  void validate(ModelProfile model, AgentContext context) {
    if (!model.remote() || !model.baseUrl().equals(configuredEndpoint) || apiKey == null || apiKey.isBlank()) {
      throw rejected("MODEL_CONFIGURATION_UNAVAILABLE");
    }
    if (!model.model().equals(context.modelVersion()) || !model.promptVersion().equals(context.promptVersion())
        || !AgentContext.TOOL_VERSION.equals(context.toolVersion()) || !AgentContext.RUNBOOK_VERSION.equals(context.runbookVersion())) {
      throw rejected("AGENT_VERSION_UNSUPPORTED");
    }
    // Conservative local byte allowance; upstream-added context is checked again against reported usage.
    if (instructions.getBytes(StandardCharsets.UTF_8).length + json.writeValueAsBytes(context).length + 256 > model.maxInputTokens()) {
      throw rejected("MODEL_INPUT_TOO_LARGE");
    }
  }

  Reply call(ModelProfile model, AgentContext context, Duration timeout) {
    validate(model, context);
    if (timeout.isNegative() || timeout.isZero()) throw rejected("RUN_TIME_BUDGET_EXCEEDED");
    // The official SDK transport disables connection retries; its policy retries are also disabled.
    var client = new OpenAIClientImpl(ClientOptions.builder()
        .httpClient(OkHttpClient.builder().timeout(timeout).build())
        .apiKey(apiKey).baseUrl(model.baseUrl()).timeout(timeout).maxRetries(0).build());
    try {
      var options = OpenAiChatOptions.builder().model(model.model()).maxTokens(model.maxOutputTokens()).build();
      var chat = OpenAiChatModel.builder().openAiClient(client).openAiClientAsync(client.async()).options(options).build();
      var response = chat.call(new Prompt(List.of(new SystemMessage(instructions), new UserMessage(json.writeValueAsString(context)))));
      var usage = response.getMetadata().getUsage();
      if (usage == null || usage instanceof EmptyUsage || usage.getPromptTokens() == null || usage.getCompletionTokens() == null
          || usage.getTotalTokens() == null || usage.getPromptTokens() <= 0 || usage.getCompletionTokens() < 0
          || (long) usage.getPromptTokens() + usage.getCompletionTokens() != usage.getTotalTokens()) {
        throw rejected("MODEL_USAGE_UNKNOWN");
      }
      String text = response.getResult() == null ? "" : response.getResult().getOutput().getText();
      return new Reply(text == null ? "" : text, model.usage(usage.getPromptTokens(), usage.getCompletionTokens()));
    } catch (ApplicationFailure rejected) { throw rejected; }
    catch (OpenAIServiceException failure) {
      int status = failure.statusCode();
      if (status >= 400 && status < 500 && status != 408 && status != 429) throw rejected("MODEL_REQUEST_REJECTED");
      throw ApplicationFailure.newFailure("Model request failed", "MODEL_TRANSPORT_FAILED");
    } catch (RuntimeException failure) {
      // Do not persist SDK exceptions or upstream bodies in Temporal history or application logs.
      throw ApplicationFailure.newFailure("Model response unavailable", "MODEL_TRANSPORT_FAILED");
    } finally { client.close(); }
  }

  private ApplicationFailure rejected(String type) { return ApplicationFailure.newNonRetryableFailure("Model request rejected", type); }
}
