package uk.codery.jclaim.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Top-level auto-configuration for JClaim. This class is intentionally a
 * minimal hook in this commit; subsequent commits add the
 * {@link uk.codery.jclaim.resolver.EntityResolver} and
 * {@link uk.codery.jclaim.event.ConflictEventSink} beans plus the
 * storage-adapter selection logic.
 */
@AutoConfiguration
public class JclaimAutoConfiguration {
}
