package com.costonomy.mp.experiment.algorithm;

import java.nio.charset.StandardCharsets;

/**
 * High-performance 32-bit MurmurHash3 implementation.
 * Provides uniform distribution and avalanche properties required for deterministic experimentation.
 */
public final class MurmurHash3 {

    private static final int C1 = 0xcc9e2d51;
    private static final int C2 = 0x1b873593;

    private MurmurHash3() {
    }

    /**
     * Hashes a string using MurmurHash3 32-bit algorithm with default seed 0.
     */
    public static int hash32(String data) {
        return hash32(data, 0);
    }

    /**
     * Hashes a string using MurmurHash3 32-bit algorithm with a specific seed.
     */
    public static int hash32(String data, int seed) {
        if (data == null) {
            return 0;
        }
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        return hash32(bytes, 0, bytes.length, seed);
    }

    /**
     * Core 32-bit MurmurHash3 logic.
     */
    public static int hash32(byte[] data, int offset, int length, int seed) {
        int h1 = seed;
        int roundedEnd = offset + (length & 0xfffffffc); // round down to 4 byte block

        for (int i = offset; i < roundedEnd; i += 4) {
            int k1 = (data[i] & 0xff)
                    | ((data[i + 1] & 0xff) << 8)
                    | ((data[i + 2] & 0xff) << 16)
                    | (data[i + 3] << 24);

            k1 *= C1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= C2;

            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }

        int k1 = 0;
        switch (length & 0x03) {
            case 3:
                k1 ^= (data[roundedEnd + 2] & 0xff) << 16;
                // fall through
            case 2:
                k1 ^= (data[roundedEnd + 1] & 0xff) << 8;
                // fall through
            case 1:
                k1 ^= (data[roundedEnd] & 0xff);
                k1 *= C1;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= C2;
                h1 ^= k1;
        }

        h1 ^= length;
        // Final avalanche mix
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;

        return h1;
    }

    /**
     * Deterministically maps a key to a bucket in [0, numBuckets - 1].
     */
    public static int bucket(String key, int numBuckets) {
        if (numBuckets <= 0) {
            throw new IllegalArgumentException("numBuckets must be positive");
        }
        int hash = hash32(key);
        // Treat as unsigned 32-bit integer to avoid negative modulo issues
        long unsignedHash = hash & 0xffffffffL;
        return (int) (unsignedHash % numBuckets);
    }
}
