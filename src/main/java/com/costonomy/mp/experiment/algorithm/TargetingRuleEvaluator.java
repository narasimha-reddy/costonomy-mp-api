package com.costonomy.mp.experiment.algorithm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;

/**
 * Evaluates dynamic targeting rules against subject runtime attributes.
 */
@Slf4j
@Component
public class TargetingRuleEvaluator {

    private final ObjectMapper objectMapper;

    public TargetingRuleEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Checks if the provided runtime attributes satisfy the experiment's targeting rules.
     * An empty or null rule set matches all subjects.
     */
    public boolean matches(String rulesJson, Map<String, Object> attributes) {
        if (rulesJson == null || rulesJson.isBlank()) {
            return true;
        }

        try {
            JsonNode root = objectMapper.readTree(rulesJson);
            if (root == null || root.isEmpty() || root.isNull()) {
                return true;
            }

            // Case 1: Structured rule engine with "conditions" array
            if (root.has("conditions") && root.get("conditions").isArray()) {
                String operator = root.has("operator") ? root.get("operator").asText("AND").toUpperCase() : "AND";
                JsonNode conditions = root.get("conditions");

                if (conditions.isEmpty()) {
                    return true;
                }

                if ("OR".equals(operator)) {
                    for (JsonNode condition : conditions) {
                        if (evaluateCondition(condition, attributes)) {
                            return true;
                        }
                    }
                    return false;
                } else {
                    // Default to AND
                    for (JsonNode condition : conditions) {
                        if (!evaluateCondition(condition, attributes)) {
                            return false;
                        }
                    }
                    return true;
                }
            }

            // Case 2: Simple key-value map format e.g. {"role": ["BUYER"], "city": "Bangalore"}
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String attrKey = entry.getKey();
                JsonNode expected = entry.getValue();

                Object actualValue = attributes != null ? attributes.get(attrKey) : null;
                if (!matchesSimpleField(expected, actualValue)) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            log.warn("Failed to evaluate targeting rules '{}' against attributes: {}", rulesJson, e.getMessage());
            return false;
        }
    }

    private boolean evaluateCondition(JsonNode condition, Map<String, Object> attributes) {
        if (!condition.has("attribute")) {
            return true;
        }
        String attrName = condition.get("attribute").asText();
        String op = condition.has("operator") ? condition.get("operator").asText("EQUALS").toUpperCase() : "EQUALS";
        Object actual = attributes != null ? attributes.get(attrName) : null;

        switch (op) {
            case "IN":
                if (!condition.has("values") || !condition.get("values").isArray()) {
                    return false;
                }
                if (actual == null) {
                    return false;
                }
                String actualStr = actual.toString();
                for (JsonNode val : condition.get("values")) {
                    if (val.asText().equalsIgnoreCase(actualStr)) {
                        return true;
                    }
                }
                return false;

            case "NOT_IN":
                if (!condition.has("values") || !condition.get("values").isArray()) {
                    return true;
                }
                if (actual == null) {
                    return true;
                }
                String actStr = actual.toString();
                for (JsonNode val : condition.get("values")) {
                    if (val.asText().equalsIgnoreCase(actStr)) {
                        return false;
                    }
                }
                return true;

            case "EQUALS":
                if (!condition.has("value") || actual == null) {
                    return false;
                }
                return condition.get("value").asText().equalsIgnoreCase(actual.toString());

            case "NOT_EQUALS":
                if (!condition.has("value")) {
                    return true;
                }
                if (actual == null) {
                    return true;
                }
                return !condition.get("value").asText().equalsIgnoreCase(actual.toString());

            case "GT":
            case "GTE":
            case "LT":
            case "LTE":
                return compareNumeric(op, condition.get("value"), actual);

            case "CONTAINS":
                if (!condition.has("value") || actual == null) {
                    return false;
                }
                return actual.toString().toLowerCase().contains(condition.get("value").asText().toLowerCase());

            default:
                return false;
        }
    }

    private boolean compareNumeric(String op, JsonNode expectedNode, Object actual) {
        if (expectedNode == null || actual == null) {
            return false;
        }
        try {
            BigDecimal expectedNum = new BigDecimal(expectedNode.asText());
            BigDecimal actualNum = new BigDecimal(actual.toString());
            int cmp = actualNum.compareTo(expectedNum);

            return switch (op) {
                case "GT" -> cmp > 0;
                case "GTE" -> cmp >= 0;
                case "LT" -> cmp < 0;
                case "LTE" -> cmp <= 0;
                default -> false;
            };
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean matchesSimpleField(JsonNode expected, Object actual) {
        if (actual == null) {
            return false;
        }
        if (expected.isArray()) {
            String actualStr = actual.toString();
            for (JsonNode item : expected) {
                if (item.asText().equalsIgnoreCase(actualStr)) {
                    return true;
                }
            }
            return false;
        }
        return expected.asText().equalsIgnoreCase(actual.toString());
    }
}
