package com.costonomy.mp.experiment;

import com.costonomy.mp.experiment.algorithm.MurmurHash3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MurmurHash3Test {

    @Test
    @DisplayName("Hash is completely deterministic across multiple invocations")
    void deterministicHashing() {
        String input = "user_98234:salt_xyz:experiment_abc";
        int hash1 = MurmurHash3.hash32(input);
        int hash2 = MurmurHash3.hash32(input);
        assertThat(hash1).isEqualTo(hash2);

        int bucket1 = MurmurHash3.bucket(input, 100);
        int bucket2 = MurmurHash3.bucket(input, 100);
        assertThat(bucket1).isEqualTo(bucket2);
    }

    @Test
    @DisplayName("Single bit flip or small character change causes dramatic hash change (avalanche effect)")
    void avalancheEffect() {
        int hashA = MurmurHash3.hash32("user_1001:salt_a");
        int hashB = MurmurHash3.hash32("user_1002:salt_a");
        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    @DisplayName("Bucket distribution is uniform across 10,000 synthetic subjects")
    void bucketUniformity() {
        int sampleSize = 20_000;
        int buckets = 10;
        int[] counts = new int[buckets];

        for (int i = 0; i < sampleSize; i++) {
            int bucket = MurmurHash3.bucket("usr_" + i + ":exp_salt", buckets);
            counts[bucket]++;
        }

        int expectedPerBucket = sampleSize / buckets; // 2,000
        for (int i = 0; i < buckets; i++) {
            // Within 15% tolerance of expected for a uniform pseudo-random hash
            assertThat(counts[i])
                    .as("Bucket %d count %d", i, counts[i])
                    .isBetween((int) (expectedPerBucket * 0.85), (int) (expectedPerBucket * 1.15));
        }
    }

    @Test
    @DisplayName("Salts provide orthogonal distribution across experiments (no cross-contamination)")
    void saltOrthogonality() {
        int sampleSize = 10_000;
        int bothBucketZero = 0;

        for (int i = 0; i < sampleSize; i++) {
            String subject = "user_" + i;
            int bucketExpA = MurmurHash3.bucket(subject + ":salt_exp_a", 2); // 50%
            int bucketExpB = MurmurHash3.bucket(subject + ":salt_exp_b", 2); // 50%

            if (bucketExpA == 0 && bucketExpB == 0) {
                bothBucketZero++;
            }
        }

        // Expected independent probability = 0.5 * 0.5 = 0.25 (2,500)
        assertThat(bothBucketZero).isBetween(2250, 2750);
    }

    @Test
    @DisplayName("Invalid bucket counts throw IllegalArgumentException")
    void invalidBucketCount() {
        assertThatThrownBy(() -> MurmurHash3.bucket("key", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MurmurHash3.bucket("key", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
