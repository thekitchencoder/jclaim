/**
 * Spring Boot auto-configuration for JClaim. Detects an {@link
 * uk.codery.jclaim.storage.EntityStorage} adapter on the classpath, wires
 * a {@link uk.codery.jclaim.resolver.EntityResolver} bean, and bridges
 * stewardship match events to Spring's {@code ApplicationEventPublisher}.
 *
 * <p>The starter never replaces Spring Data MongoDB / JDBC autoconfigurations;
 * it consumes the {@code com.mongodb.client.MongoClient} or {@link
 * javax.sql.DataSource} beans those starters provide.
 */
package uk.codery.jclaim.spring;
