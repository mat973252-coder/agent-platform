package io.github.mat973252.agentplatform.api;

import io.github.mat973252.agentplatform.core.ModelProfile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ModelConfiguration {
  @Bean
  ModelProfile modelProfile(@Value("${platform.model.provider:OFFLINE}") String provider,
      @Value("${platform.model.base-url:}") String endpoint, @Value("${platform.model.name:}") String name,
      @Value("${platform.model.pricing-version:}") String pricing,
      @Value("${platform.model.max-input-tokens:8192}") int input, @Value("${platform.model.max-output-tokens:512}") int output,
      @Value("${platform.model.input-microusd-per-million:0}") long inputPrice,
      @Value("${platform.model.output-microusd-per-million:0}") long outputPrice) {
    return provider.equals("OFFLINE") ? ModelProfile.offline() : new ModelProfile(provider, endpoint, name,
        "diagnostics-prompt-v2", pricing, input, output, inputPrice, outputPrice);
  }

  @Bean
  SpringAiPlanner springAiPlanner(ModelProfile model, @Value("${platform.model.api-key:}") String key) {
    if (model.remote() && key.isBlank()) throw new IllegalArgumentException("Remote model credentials are required");
    return new SpringAiPlanner(model.baseUrl(), key);
  }
}
