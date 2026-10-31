package com.agentflow.shared.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.math.BigDecimal;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed, validated application configuration. Every secret is injected from the environment;
 * the application refuses to start if a required value is missing.
 */
@Validated
@ConfigurationProperties(prefix = "agentflow")
public record AgentFlowProperties(
        @Valid @NotNull Security security,
        @Valid @NotNull Execution execution,
        @Valid @NotNull Kafka kafka,
        @Valid @NotNull Tools tools,
        @Valid @NotNull Ai ai) {

    public record Security(
            @NotBlank String jwtSecret,
            @NotNull Duration accessTokenTtl,
            @NotNull Duration refreshTokenTtl,
            @NotBlank String issuer) {
    }

    public record Execution(
            @Min(1) int maxStepsHardLimit,
            @NotNull Duration defaultStepTimeout,
            @NotNull Duration approvalDefaultDeadline,
            @NotNull Duration recoverySweepInterval) {
    }

    public record Kafka(
            @NotBlank String executionTopic,
            @NotBlank String executionDlqTopic,
            @NotBlank String consumerGroup) {
    }

    public record Tools(String httpAllowedHosts, @Min(1024) int maxResponseBytes) { }
    public record Ai(@NotNull @DecimalMin("0.0") BigDecimal inputUsdPer1kTokens,
                     @NotNull @DecimalMin("0.0") BigDecimal outputUsdPer1kTokens) { }
}
