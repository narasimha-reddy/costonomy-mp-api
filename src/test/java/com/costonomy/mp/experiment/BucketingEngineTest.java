package com.costonomy.mp.experiment;

import com.costonomy.mp.experiment.algorithm.BucketingEngine;
import com.costonomy.mp.experiment.domain.EvaluationReason;
import com.costonomy.mp.experiment.domain.Experiment;
import com.costonomy.mp.experiment.domain.ExperimentVariant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BucketingEngineTest {

    private BucketingEngine bucketingEngine;

    @BeforeEach
    void setUp() {
        bucketingEngine = new BucketingEngine(new ObjectMapper());
    }

    @Test
    @DisplayName("Explicit subject overrides bypass traffic and variant hashing")
    void overridesTakePrecedence() {
        Experiment experiment = new Experiment();
        experiment.setExperimentKey("override_test");
        experiment.setSalt(UUID.randomUUID().toString());
        experiment.setTrafficAllocationPct(100);
        experiment.setOverrides("{\"treatment\": [\"qa_user_1\", \"vip_100\"], \"control\": [\"qa_user_2\"]}");

        ExperimentVariant control = new ExperimentVariant("control", "Control", 50, true, null);
        ExperimentVariant treatment = new ExperimentVariant("treatment", "Treatment", 50, false, null);
        experiment.addVariant(control);
        experiment.addVariant(treatment);

        BucketingEngine.BucketingResult res1 = bucketingEngine.assign(experiment, "qa_user_1");
        assertThat(res1.reason()).isEqualTo(EvaluationReason.OVERRIDE);
        assertThat(res1.variant().getVariantKey()).isEqualTo("treatment");

        BucketingEngine.BucketingResult res2 = bucketingEngine.assign(experiment, "qa_user_2");
        assertThat(res2.reason()).isEqualTo(EvaluationReason.OVERRIDE);
        assertThat(res2.variant().getVariantKey()).isEqualTo("control");
    }

    @Test
    @DisplayName("Traffic allocation gating limits percentage of users entering experiment")
    void trafficGating() {
        Experiment experiment = new Experiment();
        experiment.setExperimentKey("traffic_test");
        experiment.setSalt("stable_salt_123");
        experiment.setTrafficAllocationPct(25); // 25% traffic

        ExperimentVariant control = new ExperimentVariant("control", "Control", 50, true, null);
        ExperimentVariant treatment = new ExperimentVariant("treatment", "Treatment", 50, false, null);
        experiment.addVariant(control);
        experiment.addVariant(treatment);

        int inTraffic = 0;
        int outOfTraffic = 0;
        int sampleSize = 10_000;

        for (int i = 0; i < sampleSize; i++) {
            BucketingEngine.BucketingResult result = bucketingEngine.assign(experiment, "subject_" + i);
            if (result.reason() == EvaluationReason.BUCKETED) {
                inTraffic++;
            } else if (result.reason() == EvaluationReason.FALLBACK_OUT_OF_TRAFFIC) {
                outOfTraffic++;
                assertThat(result.variant().isControl()).isTrue();
            }
        }

        // Expected ~25% in traffic (2,500) and ~75% out of traffic (7,500)
        assertThat(inTraffic).isBetween(2300, 2700);
        assertThat(outOfTraffic).isBetween(7300, 7700);
    }

    @Test
    @DisplayName("Variant split respects assigned weights (e.g. 80/20 split)")
    void weightedVariantSplit() {
        Experiment experiment = new Experiment();
        experiment.setExperimentKey("split_test");
        experiment.setSalt("split_salt_xyz");
        experiment.setTrafficAllocationPct(100);

        ExperimentVariant control = new ExperimentVariant("control", "Control", 80, true, null);
        ExperimentVariant treatment = new ExperimentVariant("treatment", "Treatment", 20, false, null);
        experiment.addVariant(control);
        experiment.addVariant(treatment);

        int controlCount = 0;
        int treatmentCount = 0;
        int sampleSize = 10_000;

        for (int i = 0; i < sampleSize; i++) {
            BucketingEngine.BucketingResult result = bucketingEngine.assign(experiment, "user_id_" + i);
            if ("control".equals(result.variant().getVariantKey())) {
                controlCount++;
            } else if ("treatment".equals(result.variant().getVariantKey())) {
                treatmentCount++;
            }
        }

        // Expected ~80% control (8,000) and ~20% treatment (2,000)
        assertThat(controlCount).isBetween(7700, 8300);
        assertThat(treatmentCount).isBetween(1700, 2300);
    }

    @Test
    @DisplayName("Traffic ramp-up does not flip variants for subjects already in experiment")
    void trafficRampingStability() {
        Experiment experiment20 = new Experiment();
        experiment20.setExperimentKey("ramp_test");
        experiment20.setSalt("fixed_salt");
        experiment20.setTrafficAllocationPct(20);

        ExperimentVariant c1 = new ExperimentVariant("control", "Control", 50, true, null);
        ExperimentVariant t1 = new ExperimentVariant("treatment", "Treatment", 50, false, null);
        experiment20.addVariant(c1);
        experiment20.addVariant(t1);

        Experiment experiment50 = new Experiment();
        experiment50.setExperimentKey("ramp_test");
        experiment50.setSalt("fixed_salt");
        experiment50.setTrafficAllocationPct(50);

        ExperimentVariant c2 = new ExperimentVariant("control", "Control", 50, true, null);
        ExperimentVariant t2 = new ExperimentVariant("treatment", "Treatment", 50, false, null);
        experiment50.addVariant(c2);
        experiment50.addVariant(t2);

        for (int i = 0; i < 2_000; i++) {
            String subject = "user_stability_" + i;
            BucketingEngine.BucketingResult r20 = bucketingEngine.assign(experiment20, subject);
            BucketingEngine.BucketingResult r50 = bucketingEngine.assign(experiment50, subject);

            if (r20.reason() == EvaluationReason.BUCKETED) {
                // If user was bucketed in the 20% rollout, they MUST be bucketed into the same variant in the 50% rollout
                assertThat(r50.reason()).isEqualTo(EvaluationReason.BUCKETED);
                assertThat(r50.variant().getVariantKey()).isEqualTo(r20.variant().getVariantKey());
            }
        }
    }

    @Test
    @DisplayName("Layer bucketing guarantees strict mutual exclusion across active experiments")
    void layerMutualExclusion() {
        Experiment expA = new Experiment();
        expA.setExperimentKey("exp_search_a");
        expA.setLayer("search_layer");
        expA.setSalt("salt_a");
        expA.setTrafficAllocationPct(50);
        expA.addVariant(new ExperimentVariant("control", "Control", 50, true, null));
        expA.addVariant(new ExperimentVariant("treatment", "Treatment", 50, false, null));

        Experiment expB = new Experiment();
        expB.setExperimentKey("exp_search_b");
        expB.setLayer("search_layer");
        expB.setSalt("salt_b");
        expB.setTrafficAllocationPct(50);
        expB.addVariant(new ExperimentVariant("control", "Control", 50, true, null));
        expB.addVariant(new ExperimentVariant("treatment", "Treatment", 50, false, null));

        java.util.List<Experiment> activeLayerExps = java.util.List.of(expA, expB);

        int inACount = 0;
        int inBCount = 0;
        int sampleSize = 5_000;

        for (int i = 0; i < sampleSize; i++) {
            String subject = "user_layer_" + i;
            BucketingEngine.BucketingResult resA = bucketingEngine.assign(expA, subject, activeLayerExps);
            BucketingEngine.BucketingResult resB = bucketingEngine.assign(expB, subject, activeLayerExps);

            boolean inA = resA.reason() == EvaluationReason.BUCKETED;
            boolean inB = resB.reason() == EvaluationReason.BUCKETED;

            // Strict mutual exclusion: A and B cannot both be true!
            assertThat(inA && inB)
                    .withFailMessage("Subject %s entered both experiments in the same layer!", subject)
                    .isFalse();

            if (inA) {
                inACount++;
                assertThat(resB.reason()).isEqualTo(EvaluationReason.FALLBACK_LAYER_EXCLUDED);
            } else if (inB) {
                inBCount++;
                assertThat(resA.reason()).isEqualTo(EvaluationReason.FALLBACK_LAYER_EXCLUDED);
            }
        }

        // Each experiment should get approximately 50% of the traffic
        assertThat(inACount).isBetween(2300, 2700);
        assertThat(inBCount).isBetween(2300, 2700);
        assertThat(inACount + inBCount).isEqualTo(sampleSize);
    }
}
