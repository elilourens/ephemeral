package com.ephemeral.util;

import com.github.f4b6a3.uuid.UuidCreator;

import java.time.Instant;
import java.util.UUID;

/**
 * UUIDv7 helpers. A v7 id encodes its creation time in the top 48 bits, which we
 * use as the message sort key, the pagination cursor, and the retention boundary.
 */
public final class Ids {

    private Ids() {
    }

    /** A fresh, time-ordered UUIDv7. */
    public static UUID newId() {
        return UuidCreator.getTimeOrderedEpoch();
    }

    /** The creation instant encoded in a UUIDv7. */
    public static Instant timestampOf(UUID id) {
        long millis = id.getMostSignificantBits() >>> 16; // top 48 bits = unix ms
        return Instant.ofEpochMilli(millis);
    }

    /**
     * The smallest UUIDv7 for a given instant. Used as an exclusive lower bound:
     * {@code WHERE id < boundary(cutoff)} selects everything created before cutoff.
     * Postgres compares uuids byte-wise unsigned, which matches v7 time order.
     */
    public static UUID boundary(Instant instant) {
        long millis = instant.toEpochMilli();
        long msb = (millis << 16) | 0x7000L;            // version 7, rand_a = 0
        long lsb = 0x8000000000000000L;                 // variant 0b10, rand_b = 0
        return new UUID(msb, lsb);
    }
}
