package io.github.mat973252.agentplatform.api;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
  @Bean
  UserDetailsService users(
      @Value("${platform.security.operator-username:operator}") String operator,
      @Value("${platform.security.operator-password:}") String operatorPassword,
      @Value("${platform.security.approver-username:approver}") String approver,
      @Value("${platform.security.approver-password:}") String approverPassword) {
    if (!operator.matches("[A-Za-z0-9_-]{1,64}") || !approver.matches("[A-Za-z0-9_-]{1,64}")
        || operator.equalsIgnoreCase(approver) || operatorPassword.length() < 16
        || approverPassword.length() < 16 || operatorPassword.equals(approverPassword)) {
      throw new IllegalArgumentException("Configure distinct operator/approver names and passwords of at least 16 characters");
    }
    var encoder = new BCryptPasswordEncoder();
    return new InMemoryUserDetailsManager(
        User.withUsername(operator).password("{bcrypt}" + encoder.encode(operatorPassword)).roles("OPERATOR").build(),
        User.withUsername(approver).password("{bcrypt}" + encoder.encode(approverPassword)).roles("APPROVER").build());
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .httpBasic(Customizer.withDefaults())
        // CLI clients send this custom header; cross-origin browser requests cannot add it without CORS permission.
        .csrf(csrf -> csrf.ignoringRequestMatchers(request -> "true".equals(request.getHeader("X-Platform-Request"))))
        .authorizeHttpRequests(auth -> auth
            .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
            .requestMatchers("/actuator/health").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/runs/*/approval").hasRole("APPROVER")
            .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("APPROVER", "OPERATOR")
            .requestMatchers(HttpMethod.POST, "/api/runs", "/api/runs/*/cancel").hasRole("OPERATOR")
            .anyRequest().denyAll())
        .build();
  }
}
