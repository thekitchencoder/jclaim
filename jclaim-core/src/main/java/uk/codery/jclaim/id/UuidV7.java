package uk.codery.jclaim.id;

import com.github.f4b6a3.uuid.UuidCreator;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Convenience facade for generating {@link UUID} version 7 identifiers.
 * Backed by {@code com.github.f4b6a3:uuid-creator}'s
 * {@link UuidCreator#getTimeOrderedEpoch()} which implements RFC 9562
 * (time-ordered, B-tree-friendly UUIDs).
 *
 * <p>Resolvers and storage adapters accept a {@link Supplier} so tests can
 * supply a deterministic generator without touching this class.
 */
public final class UuidV7 {

    private UuidV7() {
    }

    /** Returns a fresh RFC 9562 UUID v7. */
    public static UUID generate() {
        return UuidCreator.getTimeOrderedEpoch();
    }

    /** Returns a supplier delegating to {@link #generate()}. */
    public static Supplier<UUID> supplier() {
        return UuidV7::generate;
    }
}
