package uk.codery.jclaim.fixtures;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Supplier of UUID v7-shaped UUIDs whose ordering is deterministic for a
 * single test run. Used by the corpus integration tests so URN regex
 * validation passes without coupling to wall-clock time.
 */
public final class DeterministicUuids {

    private DeterministicUuids() {
    }

    /** Returns a fresh {@link Supplier} whose UUIDs are unique within the run. */
    public static Supplier<UUID> supplier() {
        long[] counter = {0L};
        return () -> {
            long ts = System.currentTimeMillis();
            long msb = (ts << 16) | 0x7000L | (counter[0] & 0x0FFFL);
            long lsb = 0x8000_0000_0000_0000L | (counter[0]++);
            return new UUID(msb, lsb);
        };
    }
}
