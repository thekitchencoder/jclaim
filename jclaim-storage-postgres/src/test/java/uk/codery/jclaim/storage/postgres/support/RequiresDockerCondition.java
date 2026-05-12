package uk.codery.jclaim.storage.postgres.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * JUnit 5 condition that disables Postgres adapter integration tests when
 * neither a Docker daemon nor a pre-configured Postgres
 * ({@code JCLAIM_TEST_POSTGRES_JDBC_URL}) is reachable.
 *
 * <p>Apply via {@code @ExtendWith(RequiresDockerCondition.class)}.
 */
public final class RequiresDockerCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        String envUrl = System.getenv("JCLAIM_TEST_POSTGRES_JDBC_URL");
        if (envUrl != null && !envUrl.isBlank()) {
            return ConditionEvaluationResult.enabled(
                    "Using pre-configured Postgres at " + envUrl);
        }
        try {
            if (DockerClientFactory.instance().isDockerAvailable()) {
                return ConditionEvaluationResult.enabled("Docker is available");
            }
        } catch (Throwable ignored) {
            // Testcontainers' isDockerAvailable probe can throw on misconfigured environments.
        }
        return ConditionEvaluationResult.disabled(
                "Neither Docker nor JCLAIM_TEST_POSTGRES_JDBC_URL is available; "
                        + "Postgres adapter integration tests skipped");
    }
}
