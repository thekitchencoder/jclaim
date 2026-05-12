package uk.codery.jclaim.spring.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * JUnit condition that disables the test when Docker is not available.
 * Used by the starter's Testcontainers-backed Mongo integration test to
 * skip gracefully on machines without a Docker daemon.
 */
public final class MongoDockerCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        try {
            if (DockerClientFactory.instance().isDockerAvailable()) {
                return ConditionEvaluationResult.enabled("Docker is available");
            }
        } catch (Throwable ignored) {
            // Testcontainers' probe can throw on misconfigured environments; fall through.
        }
        return ConditionEvaluationResult.disabled(
                "Docker not available; skipping Mongo Testcontainers test");
    }
}
