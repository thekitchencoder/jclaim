package uk.codery.jclaim.storage.mongo.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * JUnit 5 condition that disables Mongo adapter integration tests when
 * neither a Docker daemon nor a pre-configured MongoDB
 * ({@code JCLAIM_TEST_MONGO_URI}) is reachable.
 */
public final class RequiresDockerCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        String envUri = System.getenv("JCLAIM_TEST_MONGO_URI");
        if (envUri != null && !envUri.isBlank()) {
            return ConditionEvaluationResult.enabled(
                    "Using pre-configured MongoDB at " + envUri);
        }
        try {
            if (DockerClientFactory.instance().isDockerAvailable()) {
                return ConditionEvaluationResult.enabled("Docker is available");
            }
        } catch (Throwable ignored) {
            // probe failures fall through to disabled
        }
        return ConditionEvaluationResult.disabled(
                "Neither Docker nor JCLAIM_TEST_MONGO_URI is available; "
                        + "Mongo adapter integration tests skipped");
    }
}
