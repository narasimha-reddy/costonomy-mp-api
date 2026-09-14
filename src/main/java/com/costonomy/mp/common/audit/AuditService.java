package com.costonomy.mp.common.audit;

import com.costonomy.mp.common.web.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Writes audit records. Doc 03 §17, doc 09 §7.
 *
 * <p><b>Joins the caller's transaction on purpose.</b> An audit row and the state
 * change it describes commit together or not at all. Writing the audit in a
 * separate transaction would eventually produce a log entry for a supplier
 * suspension that was rolled back — worse than no log, because it is a record
 * that reads as authoritative and is false.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * Field names scrubbed from before/after snapshots.
     *
     * <p>Doc 09 §16 forbids OTPs, tokens and secrets reaching logs, and the audit
     * table is read by support staff, so it is held to the same rule. Matching is
     * case-insensitive and by substring, so {@code otpHash}, {@code accessToken}
     * and {@code razorpaySecret} are all caught.
     */
    private static final Set<String> REDACTED = Set.of(
            "otp", "password", "token", "secret", "signature", "authorization", "apikey", "pin");

    private static final String REDACTED_VALUE = "***";

    @Transactional
    public void record(
            Long actorId,
            String actorRole,
            String action,
            String entityType,
            Long entityId,
            String oldState,
            String newState,
            String reason,
            String source) {

        repository.save(AuditLog.builder()
                .actorId(actorId)
                .actorRole(actorRole)
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .oldState(oldState)
                .newState(newState)
                .reason(reason)
                .source(source)
                .requestId(RequestContext.requestId())
                .build());
    }

    /** A state transition, the common case. */
    @Transactional
    public void recordTransition(
            Long actorId,
            String action,
            String entityType,
            Long entityId,
            String oldState,
            String newState) {

        record(actorId, null, action, entityType, entityId, oldState, newState, null, null);
    }

    /** A change carrying before/after snapshots, both redacted before storage. */
    @Transactional
    public void recordChange(
            Long actorId,
            String action,
            String entityType,
            Long entityId,
            Object before,
            Object after,
            String reason) {

        repository.save(AuditLog.builder()
                .actorId(actorId)
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .beforeJson(redactToJson(before))
                .afterJson(redactToJson(after))
                .reason(reason)
                .requestId(RequestContext.requestId())
                .build());
    }

    private String redactToJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            var node = objectMapper.valueToTree(value);
            if (node instanceof ObjectNode object) {
                redactInPlace(object);
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            // Never fail the business transaction because a snapshot would not
            // serialise. The transition itself is still audited by the caller.
            log.warn("Could not serialise audit snapshot for {}", value.getClass().getSimpleName(), ex);
            return null;
        }
    }

    private void redactInPlace(ObjectNode node) {
        // Field names are collected first: replacing values while iterating the
        // node's own field-name iterator is only incidentally safe, and stops
        // being safe the moment this method ever needs to remove a field.
        var fields = new java.util.ArrayList<String>();
        node.fieldNames().forEachRemaining(fields::add);

        for (String field : fields) {
            String lower = field.toLowerCase(java.util.Locale.ROOT);
            if (REDACTED.stream().anyMatch(lower::contains)) {
                node.put(field, REDACTED_VALUE);
            } else if (node.get(field) instanceof ObjectNode nested) {
                redactInPlace(nested);
            }
        }
    }
}
