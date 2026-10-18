package com.costonomy.mp.experiment;

import com.costonomy.mp.experiment.algorithm.TargetingRuleEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TargetingRuleEvaluatorTest {

    private TargetingRuleEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new TargetingRuleEvaluator(new ObjectMapper());
    }

    @Test
    @DisplayName("Empty or null rules match any subject")
    void emptyRulesMatchAll() {
        assertThat(evaluator.matches(null, Map.of("role", "BUYER"))).isTrue();
        assertThat(evaluator.matches("", Map.of("role", "BUYER"))).isTrue();
        assertThat(evaluator.matches("{}", Map.of("role", "BUYER"))).isTrue();
    }

    @Test
    @DisplayName("Structured rule engine evaluates IN operator")
    void structuredRuleInOperator() {
        String ruleJson = """
                {
                  "operator": "AND",
                  "conditions": [
                    { "attribute": "role", "operator": "IN", "values": ["RESTAURANT", "SUPPLIER"] },
                    { "attribute": "city", "operator": "IN", "values": ["Bangalore", "Delhi"] }
                  ]
                }
                """;

        Map<String, Object> matching = Map.of("role", "RESTAURANT", "city", "Bangalore");
        Map<String, Object> mismatchRole = Map.of("role", "BUYER", "city", "Bangalore");
        Map<String, Object> mismatchCity = Map.of("role", "RESTAURANT", "city", "Mumbai");

        assertThat(evaluator.matches(ruleJson, matching)).isTrue();
        assertThat(evaluator.matches(ruleJson, mismatchRole)).isFalse();
        assertThat(evaluator.matches(ruleJson, mismatchCity)).isFalse();
    }

    @Test
    @DisplayName("Structured rule engine evaluates numeric comparison operators (GTE, LT)")
    void numericComparisons() {
        String ruleJson = """
                {
                  "operator": "AND",
                  "conditions": [
                    { "attribute": "orderAmount", "operator": "GTE", "value": 500 },
                    { "attribute": "appVersion", "operator": "GTE", "value": 2.5 }
                  ]
                }
                """;

        Map<String, Object> pass = Map.of("orderAmount", 750, "appVersion", 2.6);
        Map<String, Object> exactBoundary = Map.of("orderAmount", new BigDecimal("500.00"), "appVersion", "2.5");
        Map<String, Object> failAmount = Map.of("orderAmount", 499, "appVersion", 2.6);

        assertThat(evaluator.matches(ruleJson, pass)).isTrue();
        assertThat(evaluator.matches(ruleJson, exactBoundary)).isTrue();
        assertThat(evaluator.matches(ruleJson, failAmount)).isFalse();
    }

    @Test
    @DisplayName("Structured rule evaluates OR operator across conditions")
    void orConditions() {
        String ruleJson = """
                {
                  "operator": "OR",
                  "conditions": [
                    { "attribute": "isVip", "operator": "EQUALS", "value": "true" },
                    { "attribute": "tier", "operator": "IN", "values": ["GOLD", "PLATINUM"] }
                  ]
                }
                """;

        assertThat(evaluator.matches(ruleJson, Map.of("isVip", "true", "tier", "BRONZE"))).isTrue();
        assertThat(evaluator.matches(ruleJson, Map.of("isVip", "false", "tier", "GOLD"))).isTrue();
        assertThat(evaluator.matches(ruleJson, Map.of("isVip", "false", "tier", "SILVER"))).isFalse();
    }

    @Test
    @DisplayName("Simple key-value map format matching")
    void simpleMapFormat() {
        String ruleJson = """
                {
                  "role": ["RESTAURANT"],
                  "platform": "ANDROID"
                }
                """;

        assertThat(evaluator.matches(ruleJson, Map.of("role", "RESTAURANT", "platform", "android"))).isTrue();
        assertThat(evaluator.matches(ruleJson, Map.of("role", "SUPPLIER", "platform", "android"))).isFalse();
    }
}
