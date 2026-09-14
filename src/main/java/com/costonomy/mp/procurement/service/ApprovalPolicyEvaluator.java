package com.costonomy.mp.procurement.service;

import com.costonomy.mp.procurement.domain.Procurement;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decides whether a procurement needs approval. Doc 03 §15, doc 28.
 *
 * <p>The decision is the server's alone. A client cannot request approval, skip
 * it, or nominate an approver — doc 03 §15 makes this a policy evaluation, and
 * guardrail 4 makes a client-asserted state transition inadmissible.
 *
 * <p>Conditions are JSON because the axes are open-ended — order value, supplier,
 * category, outlet, payment method, requester role, and combinations (doc 28) —
 * and a column per axis would need a migration for each new one. Every condition
 * present must match; an empty condition set matches everything, which is how a
 * blanket "all orders need approval" rule is written.
 *
 * <p>The matched policy's id <em>and version</em> are recorded on the procurement.
 * Doc 09 §11: an approval is a financial control, and reconstructing why a ₹40,000
 * order was held six months ago needs the rule as it was then, not as it is now.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApprovalPolicyEvaluator {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    /**
     * @param required      whether approval is needed at all
     * @param approverRoles role codes whose holders may decide, empty when not required
     * @param reason        shown to the requester on the approval banner (§23A.18)
     */
    public record Decision(
            boolean required,
            Long policyId,
            Integer policyVersion,
            String policyName,
            List<String> approverRoles,
            String reason) {

        public static Decision notRequired() {
            return new Decision(false, null, null, null, List.of(), null);
        }
    }

    /**
     * Evaluate the policies that apply to this outlet.
     *
     * <p>Policies are considered in priority order and <b>the first match wins</b>.
     * Combining several matches would make the effective rule depend on evaluation
     * order in a way nobody could predict from reading the list; a single
     * deterministic winner is what makes a policy set explainable to the person it
     * holds up.
     */
    @Transactional(readOnly = true)
    public Decision evaluate(Procurement procurement, Long restaurantId, String requesterRole,
                             List<Long> supplierStoreIds, List<Long> categoryIds) {

        var policies = jdbc.query("""
                select id, name, conditions_json, approver_roles_json, policy_version, priority
                  from procurement_policy
                 where restaurant_id = ?
                   and (outlet_id is null or outlet_id = ?)
                   and status = 'ACTIVE'
                   and effective_from <= utc_timestamp(6)
                   and (effective_to is null or effective_to > utc_timestamp(6))
                 order by priority asc, id asc
                """,
                (rs, i) -> new PolicyRow(
                        rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5)),
                restaurantId, procurement.getOutletId());

        for (PolicyRow policy : policies) {
            Optional<String> reason = matches(policy, procurement, requesterRole,
                    supplierStoreIds, categoryIds);
            if (reason.isPresent()) {
                return new Decision(true, policy.id(), policy.version(), policy.name(),
                        parseRoles(policy.approverRolesJson()), reason.get());
            }
        }
        return Decision.notRequired();
    }

    /**
     * Whether a policy matches, and why.
     *
     * <p>Returns the human-readable reason alongside the verdict rather than
     * recomputing it later: §23A.18 requires the approver to be shown which policy
     * triggered this and why, and a reason reconstructed after the fact can drift
     * from the condition that actually fired.
     */
    private Optional<String> matches(PolicyRow policy, Procurement procurement,
                                     String requesterRole, List<Long> supplierStoreIds,
                                     List<Long> categoryIds) {
        JsonNode conditions;
        try {
            conditions = json.readTree(policy.conditionsJson());
        } catch (Exception ex) {
            // A malformed policy must not silently stop holding orders. Skipping it
            // fails open, which is wrong for a financial control — so it is logged
            // loudly, and the remaining policies still apply.
            log.error("Approval policy {} has unreadable conditions — skipping", policy.id(), ex);
            return Optional.empty();
        }

        List<String> reasons = new ArrayList<>();

        if (conditions.hasNonNull("minOrderValue")) {
            BigDecimal threshold = new BigDecimal(conditions.get("minOrderValue").asText());
            if (procurement.getTotalAmount().compareTo(threshold) < 0) {
                return Optional.empty();
            }
            reasons.add("order value is ₹%s or more".formatted(threshold.toPlainString()));
        }

        if (conditions.hasNonNull("paymentMethods")) {
            var methods = toStrings(conditions.get("paymentMethods"));
            if (!methods.contains(procurement.getPaymentMethod())) {
                return Optional.empty();
            }
            reasons.add("payment method is " + procurement.getPaymentMethod());
        }

        if (conditions.hasNonNull("requesterRoles")) {
            var roles = toStrings(conditions.get("requesterRoles"));
            if (requesterRole == null || !roles.contains(requesterRole)) {
                return Optional.empty();
            }
            reasons.add("requested by " + requesterRole);
        }

        if (conditions.hasNonNull("supplierStoreIds")) {
            var ids = toLongs(conditions.get("supplierStoreIds"));
            if (supplierStoreIds.stream().noneMatch(ids::contains)) {
                return Optional.empty();
            }
            reasons.add("includes a supplier that requires approval");
        }

        if (conditions.hasNonNull("categoryIds")) {
            var ids = toLongs(conditions.get("categoryIds"));
            if (categoryIds.stream().noneMatch(ids::contains)) {
                return Optional.empty();
            }
            reasons.add("includes a category that requires approval");
        }

        // No conditions at all means "every order", which is a legitimate policy
        // and needs a reason the approver can read.
        return Optional.of(reasons.isEmpty()
                ? "All orders at this outlet require approval"
                : "Approval required because " + String.join(", and ", reasons));
    }

    private List<String> parseRoles(String raw) {
        try {
            return toStrings(json.readTree(raw));
        } catch (Exception ex) {
            log.error("Approval policy has unreadable approver roles", ex);
            return List.of();
        }
    }

    private static List<String> toStrings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static List<Long> toLongs(JsonNode array) {
        List<Long> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asLong()));
        return values;
    }

    private record PolicyRow(Long id, String name, String conditionsJson,
                             String approverRolesJson, Integer version) {
    }
}
