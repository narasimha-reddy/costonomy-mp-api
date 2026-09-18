package com.costonomy.mp.intent.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.catalog.repository.SupplierOfferRepository;
import com.costonomy.mp.catalog.service.SkuDirectory;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.intent.domain.Intent;
import com.costonomy.mp.intent.domain.IntentAcceptance;
import com.costonomy.mp.intent.domain.IntentAcceptanceItem;
import com.costonomy.mp.intent.domain.IntentAcceptanceStatus;
import com.costonomy.mp.intent.domain.IntentItem;
import com.costonomy.mp.intent.domain.IntentPolicy;
import com.costonomy.mp.intent.domain.IntentStatus;
import com.costonomy.mp.intent.repository.IntentAcceptanceItemRepository;
import com.costonomy.mp.intent.repository.IntentAcceptanceRepository;
import com.costonomy.mp.intent.repository.IntentItemRepository;
import com.costonomy.mp.intent.repository.IntentRepository;
import com.costonomy.mp.intent.web.dto.IntentDtos;
import com.costonomy.mp.procurement.domain.Pricing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Records what a supplier will supply, in one transaction. §10, §11.
 *
 * <p>A <b>separate bean</b> from {@link IntentAcceptanceService} for the reason
 * documented on {@code ProcurementSubmitter} and {@code IdempotencyStore}:
 * {@code @Transactional} works through a proxy, so a method called through
 * {@code this} — including from inside the idempotency lambda — never reaches it
 * and the annotation is silently ignored. This writes an acceptance, its lines and
 * a transition on the intent; without a transaction a failure part-way would
 * leave an intent answered with half an answer.
 *
 * <p><b>The supplier does not send prices.</b> Every line is priced from that
 * store's live {@code ACTIVE} offer and run through {@code Pricing}. A supplier
 * changes a price by superseding an offer in their catalogue (D-012), never by
 * typing a number into a response — otherwise the price a restaurant compared on
 * the product screen and the price it is charged could differ with nothing to
 * reconcile them, and guardrail 3 would be a comment rather than a rule.
 *
 * <p><b>The answer is complete or it is refused.</b> Every line must appear. An
 * omitted line read as zero would turn a supplier's slip — a client dropping a
 * row, a scroll that never reached the bottom — into a refusal they never made,
 * and the restaurant would be told a product was unavailable when nobody had said
 * so. §12 wants the unmet quantity explicit, and "explicit" has to include the
 * supplier explicitly saying nothing is available.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IntentResponder {

    private final IntentRepository intents;
    private final IntentItemRepository intentItems;
    private final IntentAcceptanceRepository acceptances;
    private final IntentAcceptanceItemRepository acceptanceItems;
    private final SupplierOfferRepository offers;
    private final SkuDirectory skuDirectory;
    private final IntentMapper mapper;
    private final IntentPolicy policy;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final OutboxService outbox;

    @Transactional
    public IntentDtos.IntentResponse respond(
            Long actorId, Long intentId, IntentDtos.RespondRequest request) {

        var intent = intents.findById(intentId)
                .orElseThrow(() -> new NotFoundException("Intent", intentId));

        // Scoped to the store the request was sent to. A 404 rather than a 403,
        // so a supplier cannot walk ids to discover other stores' requests
        // (doc 09 §3).
        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW,
                ScopeType.SUPPLIER_STORE, intent.getSupplierStoreId(), "Intent");

        // Already answered. Return the answer rather than failing: a retry whose
        // idempotency key was lost is a normal client outcome, and the caller's
        // real question — "did my response go through?" — has a true answer.
        if (intent.getStatus() == IntentStatus.RESPONSES_RECEIVED) {
            return mapper.toResponse(intent);
        }
        if (intent.getStatus() != IntentStatus.OPEN) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    switch (intent.getStatus()) {
                        case DRAFT -> "This request hasn't been sent yet.";
                        case EXPIRED -> "This request expired before it was answered.";
                        case CANCELLED -> "The restaurant cancelled this request.";
                        default -> "This request is no longer open.";
                    });
        }

        // The request as the supplier last read it, or a refusal.
        //
        // A restaurant may change quantities while a request is open (D-088), so
        // the list a supplier is looking at can move under them. Accepting 4 KG
        // of something cut to 2 an instant earlier would commit stock nobody
        // asked for, and the supplier would find out at delivery. The revision is
        // the intent's own version, which quantity edits and line removals
        // force-bump so that it tracks the content rather than just the row.
        if (request != null && request.expectedRevision() != null
                && intent.getVersion() != null
                && !request.expectedRevision().equals(intent.getVersion())) {
            throw new BusinessException(ErrorCode.INTENT_CHANGED,
                    "This request changed while you were reading it. "
                            + "Have another look before you accept.");
        }

        // The deadline, checked here rather than trusted from the sweep. The job
        // runs every thirty seconds; this makes "a supplier cannot answer a
        // request that has expired" true at every instant in between, which is
        // the same division of labour SupplierOrderTransitions has with its
        // timeout job.
        if (!intent.withinResponseWindow(Instant.now())) {
            throw new BusinessException(ErrorCode.INTENT_EXPIRED,
                    "The time to answer this request has passed.");
        }

        var lines = intentItems.findByIntentIdOrderByIdAsc(intentId);
        var answers = index(request, lines);

        // Which permission this needs depends on what is being said, so the shape
        // of the answer is worked out before anything is written. A user who may
        // accept but not partially accept can answer in full and no other way —
        // the same split the order flow had, moved to where the decision now is.
        requirePermissionFor(actorId, intent, lines, answers);

        Instant now = Instant.now();
        int windowSeconds = policy.orderCreationWindowSeconds();
        Instant deadline = policy.orderCreationDeadline(now, windowSeconds);

        var acceptance = new IntentAcceptance();
        acceptance.setIntentId(intent.getId());
        acceptance.setSupplierStoreId(intent.getSupplierStoreId());
        acceptance.setRespondedBy(actorId);
        acceptance.setStatus(IntentAcceptanceStatus.SUBMITTED);
        acceptance.setEtaMinutes(request.etaMinutes());
        acceptance.setDeliveryMode(request.deliveryMode());
        acceptance.setNotes(request.notes());
        acceptance.setSubmittedAt(now);
        // The same instant as the order-creation deadline, deliberately: an offer
        // stands for exactly as long as it can be ordered against. See IntentPolicy.
        acceptance.setExpiresAt(deadline);
        // Delivery is quoted when a courier is assigned, not here. Doc 06 §4 and
        // OPEN-005: a figure invented now would appear on the restaurant's total
        // and then change.
        acceptance.setDeliveryFee(null);
        acceptances.saveAndFlush(acceptance);

        var priced = priceLines(intent, lines, answers);

        BigDecimal value = BigDecimal.ZERO;
        BigDecimal gst = BigDecimal.ZERO;
        for (IntentAcceptanceItem line : priced) {
            line.setIntentAcceptanceId(acceptance.getId());
            acceptanceItems.save(line);
            value = value.add(line.getLineValue());
            gst = gst.add(line.getLineGst());
        }

        acceptance.setOfferedValue(Pricing.money(value));
        acceptance.setOfferedGst(Pricing.money(gst));
        acceptance.setOfferedTotal(Pricing.money(value.add(gst)));
        acceptances.save(acceptance);

        var from = intent.getStatus();
        if (!from.canTransitionTo(IntentStatus.RESPONSES_RECEIVED)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "A request at %s cannot be answered.".formatted(from));
        }
        intent.setStatus(IntentStatus.RESPONSES_RECEIVED);
        intent.setAcceptedAt(now);
        // Both, or ck_intent_window rejects the row — and the constraint is there
        // so the order endpoint can never fall back on a default and guess.
        intent.setAcceptedOrderCreationWindowSeconds(windowSeconds);
        intent.setOrderCreationDeadline(deadline);
        intents.save(intent);

        auditService.record(actorId, null, "INTENT_ANSWERED", "INTENT", intent.getId(),
                from.name(), IntentStatus.RESPONSES_RECEIVED.name(),
                "offered %s".formatted(acceptance.getOfferedTotal().toPlainString()), "API");

        // Two events, because they are two different things to be told.
        //
        // A reply with something in it is good news on a clock: order inside the
        // window. A reply with nothing in it is the supplier saying no, and the
        // kitchen has to source those goods from somebody else today -- which is
        // what earns it an SMS, exactly as an outright order rejection did.
        // Publishing one event for both would force the notification to either
        // cry wolf on every reply or stay quiet on the refusals.
        boolean nothingOffered = priced.stream()
                .allMatch(line -> line.getOfferedQuantity().signum() == 0);

        outbox.publish(nothingOffered ? "IntentDeclined" : "IntentAnswered",
                "INTENT", intent.getId(),
                Map.of("reference", intent.getReference(),
                        "outletId", intent.getOutletId(),
                        "supplierStoreId", intent.getSupplierStoreId(),
                        "offeredTotal", acceptance.getOfferedTotal().toPlainString(),
                        "orderCreationDeadline", deadline.toString()),
                actorId, now);

        return mapper.toResponse(intent);
    }

    /**
     * The answers, checked against the request they answer.
     *
     * <p>Three ways this can be wrong, each reported as itself rather than as a
     * generic validation failure: a line missing, a line that belongs to another
     * request, and a line offered twice.
     */
    private Map<Long, IntentDtos.RespondLine> index(
            IntentDtos.RespondRequest request, List<IntentItem> lines) {

        Map<Long, IntentDtos.RespondLine> answers = new LinkedHashMap<>();
        for (IntentDtos.RespondLine line : request.lines()) {
            if (answers.put(line.intentItemId(), line) != null) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Line %d was answered twice.".formatted(line.intentItemId()));
            }
        }

        var known = lines.stream().map(IntentItem::getId).collect(Collectors.toSet());
        for (Long answered : answers.keySet()) {
            if (!known.contains(answered)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Line %d isn't part of this request.".formatted(answered));
            }
        }

        var missing = lines.stream()
                .map(IntentItem::getId)
                .filter(id -> !answers.containsKey(id))
                .toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Answer every line. %d still %s no answer — say zero to decline one."
                            .formatted(missing.size(), missing.size() == 1 ? "has" : "have"));
        }
        return answers;
    }

    /**
     * Price every line from what the request was sent at.
     *
     * <p><b>The locked price, not today's catalogue.</b> The restaurant saw a
     * real figure in their basket, confirmed any reprice, and sent on that basis;
     * re-pricing here would make that figure a lie and hand the supplier a
     * unilateral change between the asking and the answering. A supplier who no
     * longer wants to sell at it declines the line, which is an answer they can
     * always give — and far better than a silent reprice nobody agreed to.
     *
     * <p>A line with no snapshot cannot be priced at all, and is refused rather
     * than guessed at.
     */
    private List<IntentAcceptanceItem> priceLines(
            Intent intent, List<IntentItem> lines, Map<Long, IntentDtos.RespondLine> answers) {

        var labels = skuDirectory.describe(lines.stream().map(IntentItem::getSupplierSkuId).toList());

        List<IntentAcceptanceItem> priced = new ArrayList<>(lines.size());
        for (IntentItem line : lines) {
            var answer = answers.get(line.getId());
            var offered = answer.offeredQuantity();

            if (offered.compareTo(line.getRequestedQuantity()) > 0) {
                var label = labels.get(line.getSupplierSkuId());
                throw new BusinessException(ErrorCode.ACCEPTED_QUANTITY_EXCEEDS_REQUESTED,
                        "%s: they asked for %s %s and you offered %s."
                                .formatted(label == null ? "This line" : label.productName(),
                                        line.getRequestedQuantity().toPlainString(),
                                        line.getUnit(), offered.toPlainString()));
            }

            boolean declined = offered.signum() == 0;

            if (!declined && line.getUnitPriceSnapshot() == null) {
                var label = labels.get(line.getSupplierSkuId());
                throw new BusinessException(ErrorCode.SKU_UNAVAILABLE,
                        ("%s was sent without a price, so it cannot be supplied. "
                                + "Offer zero to decline it.")
                                .formatted(label == null ? "That product" : label.productName()));
            }

            // Declining needs no price, so a line whose price never arrived can
            // still be refused — the one answer that must always be available.
            BigDecimal unitPrice = line.getUnitPriceSnapshot() == null
                    ? BigDecimal.ZERO : line.getUnitPriceSnapshot();
            BigDecimal gstRate = line.getGstRateSnapshot() == null
                    ? BigDecimal.ZERO : line.getGstRateSnapshot();

            var item = new IntentAcceptanceItem();
            item.setIntentItemId(line.getId());
            item.setOfferedQuantity(offered);
            item.setUnitPrice(unitPrice);
            item.setGstRate(gstRate);

            var lineValue = Pricing.lineItemValue(unitPrice, offered);
            var lineGst = Pricing.lineGst(lineValue, gstRate);
            item.setLineValue(lineValue);
            item.setLineGst(lineGst);
            item.setLineTotal(Pricing.lineTotal(lineValue, lineGst));
            item.setAvailability(declined ? "OUT_OF_STOCK" : "AVAILABLE");
            item.setNotes(answer.notes());
            priced.add(item);
        }
        return priced;
    }

    /**
     * The permission this particular answer needs.
     *
     * <p>Answering in full, answering short and refusing outright are three
     * different commercial acts, and the order flow already permissioned them
     * separately. Keeping that split here means a store can let a warehouse hand
     * confirm what is in stock without also letting them turn business away.
     */
    private void requirePermissionFor(Long actorId, Intent intent, List<IntentItem> lines,
                                      Map<Long, IntentDtos.RespondLine> answers) {

        boolean anyOffered = false;
        boolean anyShort = false;
        for (IntentItem line : lines) {
            var offered = answers.get(line.getId()).offeredQuantity();
            if (offered.signum() > 0) {
                anyOffered = true;
            }
            if (offered.compareTo(line.getRequestedQuantity()) < 0) {
                anyShort = true;
            }
        }

        String permission = !anyOffered
                ? Permissions.ORDER_REJECT
                : anyShort ? Permissions.ORDER_PARTIAL_ACCEPT : Permissions.ORDER_ACCEPT;

        accessControl.requireScoped(actorId, permission,
                ScopeType.SUPPLIER_STORE, intent.getSupplierStoreId(), "Intent");
    }

    /**
     * What this reply would come to, without sending it.
     *
     * <p>Read-only, and deliberately forgiving: an over-large quantity is
     * clamped to what was asked for rather than refused, because a supplier
     * dragging a stepper wants a number back, not an error. The strict checks
     * belong on {@link #respond}, which is where the commitment is made.
     */
    @Transactional(readOnly = true)
    public IntentDtos.RespondPreviewResponse preview(
            Long actorId, Long intentId, IntentDtos.RespondRequest request) {

        var intent = intents.findById(intentId)
                .orElseThrow(() -> new NotFoundException("Intent", intentId));
        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW,
                ScopeType.SUPPLIER_STORE, intent.getSupplierStoreId(), "Intent");

        var lines = intentItems.findByIntentIdOrderByIdAsc(intentId);
        var labels = skuDirectory.describe(lines.stream().map(IntentItem::getSupplierSkuId).toList());

        Map<Long, BigDecimal> wanted = new HashMap<>();
        if (request != null && request.lines() != null) {
            request.lines().forEach(line ->
                    wanted.put(line.intentItemId(), line.offeredQuantity()));
        }

        var previewLines = new ArrayList<IntentDtos.RespondPreviewLine>(lines.size());
        BigDecimal value = BigDecimal.ZERO;
        BigDecimal gst = BigDecimal.ZERO;

        for (IntentItem line : lines) {
            var requested = line.getRequestedQuantity();
            var asked = wanted.getOrDefault(line.getId(), requested);
            // Clamped, not rejected — see above.
            var offered = asked == null || asked.signum() < 0
                    ? BigDecimal.ZERO
                    : asked.min(requested);

            var unitPrice = line.getUnitPriceSnapshot();
            var gstRate = line.getGstRateSnapshot();
            BigDecimal lineValue = null;
            BigDecimal lineGst = null;
            BigDecimal lineTotal = null;

            if (unitPrice != null && gstRate != null) {
                lineValue = Pricing.lineItemValue(unitPrice, offered);
                lineGst = Pricing.lineGst(lineValue, gstRate);
                lineTotal = Pricing.lineTotal(lineValue, lineGst);
                value = value.add(lineValue);
                gst = gst.add(lineGst);
            }

            var label = labels.get(line.getSupplierSkuId());
            previewLines.add(new IntentDtos.RespondPreviewLine(
                    line.getId(),
                    label == null ? null : label.productName(),
                    requested, offered, line.getUnit(),
                    unitPrice, gstRate, lineValue, lineGst, lineTotal));
        }

        return new IntentDtos.RespondPreviewResponse(
                intentId, previewLines,
                Pricing.money(value), Pricing.money(gst), Pricing.money(value.add(gst)));
    }

    /** Batched projections for the supplier's own list screens. */
    @Transactional(readOnly = true)
    public List<IntentDtos.IntentResponse> forStore(
            Long actorId, Long storeId, List<IntentStatus> statuses, int limit) {

        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");

        var found = intents.findForStore(storeId, statuses);
        return mapper.toResponses(found.size() > limit ? found.subList(0, limit) : found);
    }
}
