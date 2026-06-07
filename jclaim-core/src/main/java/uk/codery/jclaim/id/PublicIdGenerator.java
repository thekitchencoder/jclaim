package uk.codery.jclaim.id;

/**
 * Mints a fresh candidate for the human-facing <em>public</em> identifier shown
 * alongside an entity's URN. This single method is the only thing the resolver
 * depends on — uniqueness is the resolver's concern, so a candidate need not be
 * unique. Alphabet, entropy source, length, and check-digit scheme are all
 * implementation details of a particular generator, not part of this contract,
 * so radically different generators (random Crockford, sequential, UUID/ULID,
 * externally supplied) all sit behind it.
 */
@FunctionalInterface
public interface PublicIdGenerator {

    /** A fresh candidate public ID. Need not be unique. */
    String generate();
}
