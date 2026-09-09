package io.github.mat973252.agentplatform.core;

import java.net.URI;

/** Immutable, non-secret model and estimate parameters captured when a Run is created. */
public record ModelProfile(String provider, String baseUrl, String model, String promptVersion,
    String pricingVersion, int maxInputTokens, int maxOutputTokens,
    long inputMicrousdPerMillion, long outputMicrousdPerMillion) {
  public ModelProfile {
    if (!"OFFLINE".equals(provider) && !"OPENAI_COMPATIBLE".equals(provider)) {
      throw new IllegalArgumentException("Unsupported model provider");
    }
    if (model == null || !model.matches("[A-Za-z0-9_./:-]{1,128}") || promptVersion == null
        || pricingVersion == null || !pricingVersion.matches("[A-Za-z0-9_.:-]{1,128}")
        || maxInputTokens < 1 || maxInputTokens > 100000 || maxOutputTokens < 1 || maxOutputTokens > 8192
        || inputMicrousdPerMillion < 0 || inputMicrousdPerMillion > 1_000_000_000L
        || outputMicrousdPerMillion < 0 || outputMicrousdPerMillion > 1_000_000_000L) {
      throw new IllegalArgumentException("Invalid model profile");
    }
    if ("OPENAI_COMPATIBLE".equals(provider)) {
      URI uri = URI.create(baseUrl);
      boolean local = "http".equals(uri.getScheme()) && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()));
      if ((!"https".equals(uri.getScheme()) && !local) || uri.getHost() == null || uri.getUserInfo() != null
          || uri.getQuery() != null || uri.getFragment() != null || baseUrl.length() > 512
          || !"diagnostics-prompt-v2".equals(promptVersion)
          || inputMicrousdPerMillion == 0 || outputMicrousdPerMillion == 0) {
        throw new IllegalArgumentException("Remote model requires a trusted endpoint and explicit versioned prices");
      }
    }
  }

  public boolean remote() { return provider.equals("OPENAI_COMPATIBLE"); }
  public String meteringMode() { return remote() ? "PROVIDER_USAGE_CONFIGURED_ESTIMATE" : "OFFLINE_SIMULATED"; }
  public ModelUsage usage(long input, long output) {
    long cost = (Math.addExact(Math.multiplyExact(input, inputMicrousdPerMillion),
        Math.multiplyExact(output, outputMicrousdPerMillion)) + 999999) / 1000000;
    return new ModelUsage(input, output, cost);
  }
  public ModelUsage allowance() { return remote() ? usage(maxInputTokens, maxOutputTokens) : new ModelUsage(2048, 512, 5000); }
  public static ModelProfile offline() {
    return new ModelProfile("OFFLINE", "", AgentContext.MODEL_VERSION, AgentContext.PROMPT_VERSION,
        "offline-price-v1", 2048, 512, 0, 0);
  }
}
