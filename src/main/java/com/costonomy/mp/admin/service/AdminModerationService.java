package com.costonomy.mp.admin.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.admin.web.dto.AdminDtos;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * The mutations operations is allowed to make. Doc 09 §9, §12.
 *
 * <p>Separate from {@link AdminQueryService} because doc 09 §13 separates
 * inspection from mutation, and the separation only means something if the
 * permissions differ — which they do: everything here needs a specific write
 * permission that the read half does not imply.
 *
 * <p><b>Every mutation is audited with a reason, and the reason is required.</b>
 * Doc 09 §7 and §9. An operator suspending a supplier is making a commercial
 * decision about someone else's business; an unexplained suspension is
 * indistinguishable from a mistake, and the supplier asking why deserves an answer
 * that exists.
 *
 * <p><b>Operations does not act as a tenant.</b> Nothing here approves an order,
 * accepts on a supplier's behalf, or moves money. Suspension stops new trade;
 * moderation hides content; configuration changes policy. The line is that
 * operations changes what is *possible*, never what a party *decided*.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminModerationService {

    private final JdbcTemplate jdbc;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final OutboxService outbox;

    // ── Suppliers ────────────────────────────────────────────────────────

    /**
     * Stop a supplier trading. Doc 09 §9.
     *
     * <p>Their existing orders are untouched: a supplier suspended today still owes
     * the deliveries they accepted yesterday, and cancelling them would punish the
     * restaurants rather than the supplier. Suspension stops new orders, which the
     * serviceability and submission checks already enforce by requiring an ACTIVE
     * organisation.
     */
    @Transactional
    public void suspendSupplier(Long actorId, Long supplierId, String reason) {
        accessControl.require(actorId, Permissions.SUPPLIER_SUSPEND, ScopeType.PLATFORM, null);

        String current = lifecycleOf(supplierId);
        if ("SUSPENDED".equals(current)) {
            return;
        }

        jdbc.update("""
                update supplier_organization
                   set lifecycle_status = 'SUSPENDED', version = version + 1, updated_at = now(6)
                 where id = ?
                """, supplierId);

        auditService.record(actorId, null, "SUPPLIER_SUSPENDED", "SUPPLIER",
                supplierId, current, "SUSPENDED", reason, "ADMIN");

        outbox.publish("SupplierSuspended", "SUPPLIER", supplierId,
                Map.of("reason", reason), actorId);

        log.info("Supplier {} suspended by operator {}", supplierId, actorId);
    }

    @Transactional
    public void reactivateSupplier(Long actorId, Long supplierId, String reason) {
        accessControl.require(actorId, Permissions.SUPPLIER_SUSPEND, ScopeType.PLATFORM, null);

        String current = lifecycleOf(supplierId);
        if (!"SUSPENDED".equals(current)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This supplier isn't suspended.");
        }

        // Back to ACTIVE, not to whatever they were before. A suspended supplier
        // who was mid-verification must not be silently promoted past it.
        jdbc.update("""
                update supplier_organization
                   set lifecycle_status = 'ACTIVE', version = version + 1, updated_at = now(6)
                 where id = ? and verification_status = 'VERIFIED'
                """, supplierId);

        auditService.record(actorId, null, "SUPPLIER_REACTIVATED", "SUPPLIER",
                supplierId, current, "ACTIVE", reason, "ADMIN");
    }

    private String lifecycleOf(Long supplierId) {
        var rows = jdbc.queryForList(
                "select lifecycle_status from supplier_organization where id = ?",
                String.class, supplierId);
        if (rows.isEmpty()) {
            throw new NotFoundException("Supplier", supplierId);
        }
        return rows.get(0);
    }

    // ── Catalog ──────────────────────────────────────────────────────────

    /**
     * Disable a SKU. Doc 09 §9.
     *
     * <p>Disabled, not deleted, and its offers are closed rather than removed —
     * doc 02 §4: a historical commercial value must survive a transaction that
     * references it. An order placed last week was placed at a price, and deleting
     * the SKU would make that order unreconstructable.
     */
    @Transactional
    public void disableSku(Long actorId, Long skuId, String reason) {
        accessControl.require(actorId, Permissions.CATALOG_MODERATE, ScopeType.PLATFORM, null);

        int applied = jdbc.update("""
                update supplier_sku
                   set status = 'DISABLED', version = version + 1, updated_at = now(6)
                 where id = ? and status <> 'DISABLED'
                """, skuId);

        if (applied == 0) {
            throw new NotFoundException("SupplierSku", skuId);
        }

        // Close the live offer too, or the SKU would be unlisted while its price
        // stayed purchasable through a stale search result.
        jdbc.update("""
                update supplier_offer
                   set status = 'SUPERSEDED', effective_to = now(6),
                       version = version + 1, updated_at = now(6)
                 where supplier_sku_id = ? and status = 'ACTIVE'
                """, skuId);

        auditService.record(actorId, null, "SKU_DISABLED", "SUPPLIER_SKU",
                skuId, "ACTIVE", "DISABLED", reason, "ADMIN");
    }

    /**
     * Correct a canonical product. Doc 09 §9.
     *
     * <p>Canonical products are platform-owned (doc 01 §7) and are the axis every
     * comparison turns on, so a typo in one costs every supplier mapped to it.
     * Renaming re-normalises, because search reads the normalised form.
     */
    @Transactional
    public void renameCanonicalProduct(Long actorId, Long productId, String name, String reason) {
        accessControl.require(actorId, Permissions.CATALOG_MODERATE, ScopeType.PLATFORM, null);

        var rows = jdbc.queryForList(
                "select name from canonical_product where id = ?", String.class, productId);
        if (rows.isEmpty()) {
            throw new NotFoundException("CanonicalProduct", productId);
        }

        jdbc.update("""
                update canonical_product
                   set name = ?, normalized_name = ?, version = version + 1, updated_at = now(6)
                 where id = ?
                """, name, com.costonomy.mp.catalog.domain.Normalization.normalize(name),
                productId);

        auditService.record(actorId, null, "CANONICAL_PRODUCT_RENAMED", "CANONICAL_PRODUCT",
                productId, rows.get(0), name, reason, "ADMIN");
    }

    /**
     * Set or clear a canonical product's picture. Doc 01 §7, doc 05 §29.
     *
     * <p>An ops operation rather than a supplier one, for the same reason the
     * product itself is: the image is the face of the thing every supplier's SKU
     * maps onto, and two suppliers' paneer must show the same paneer or the
     * comparison the marketplace exists for stops being a comparison.
     *
     * <p>A blank URL <b>clears</b> it rather than being rejected. A wrong picture
     * on a food product is worse than none — a restaurant orders from it — so
     * taking one down has to be as easy as putting one up, and the app renders a
     * neutral fallback for a product with no image.
     */
    public void setCanonicalProductImage(Long actorId, Long productId, String imageUrl,
                                         String reason) {
        accessControl.require(actorId, Permissions.CATALOG_MODERATE, ScopeType.PLATFORM, null);

        var rows = jdbc.queryForList(
                "select image_url from canonical_product where id = ?", String.class, productId);
        if (rows.isEmpty()) {
            throw new NotFoundException("CanonicalProduct", productId);
        }

        String cleaned = imageUrl == null || imageUrl.isBlank() ? null : imageUrl.trim();

        jdbc.update("""
                update canonical_product
                   set image_url = ?, version = version + 1, updated_at = now(6)
                 where id = ?
                """, cleaned, productId);

        // recordChange, not record: old_state and new_state are varchar(64) and
        // hold state-machine states. A URL is not a state, and putting one there
        // truncated the column and failed the whole request. The before/after
        // snapshots are JSON and are where a value of any length belongs.
        auditService.recordChange(actorId,
                cleaned == null ? "CANONICAL_PRODUCT_IMAGE_CLEARED" : "CANONICAL_PRODUCT_IMAGE_SET",
                "CANONICAL_PRODUCT", productId,
                Map.of("imageUrl", String.valueOf(rows.get(0))),
                Map.of("imageUrl", String.valueOf(cleaned)),
                reason);
    }

    /**
     * Set a store's answer window. Doc 13, doc 09 §17.
     *
     * <p>An operations decision, not a supplier's. A supplier who could set their
     * own window could set it to an hour and never be late again, and "responds
     * quickly" would stop meaning anything to compare across the marketplace —
     * the number is also what a restaurant's countdown is measured against, so it
     * belongs to whoever is accountable for that promise rather than to the party
     * being held to it.
     *
     * <p>Live orders keep the window they were created with: the deadline is
     * snapshotted onto the order, and doc 13 is explicit that changing an SLA must
     * not move a countdown already running.
     */
    public void setResponseSla(Long actorId, Long storeId, int seconds, String reason) {
        accessControl.require(actorId, Permissions.CATALOG_MODERATE, ScopeType.PLATFORM, null);

        if (seconds < 10 || seconds > 86_400) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "The answer window must be between 10 seconds and 24 hours.");
        }

        var rows = jdbc.queryForList(
                "select response_sla_seconds from supplier_store where id = ?",
                Integer.class, storeId);
        if (rows.isEmpty()) {
            throw new NotFoundException("SupplierStore", storeId);
        }

        jdbc.update("""
                update supplier_store
                   set response_sla_seconds = ?, version = version + 1, updated_at = now(6)
                 where id = ?
                """, seconds, storeId);

        auditService.record(actorId, null, "SUPPLIER_STORE_SLA_CHANGED", "SUPPLIER_STORE",
                storeId, String.valueOf(rows.get(0)), String.valueOf(seconds), reason, "ADMIN");
    }

    // ── Disputes ─────────────────────────────────────────────────────────

    /**
     * Close a dispute the two parties could not close themselves. Doc 09 §9.
     *
     * <p><b>Recorded, not adjudicated.</b> Doc 01 §23 is unchanged by an operator
     * being involved: Mandi does not move money between a restaurant and a
     * supplier, and this writes down an outcome the parties reached with help. The
     * note is stored as an <em>internal</em> message, which neither party sees
     * (§23A.32) — the resolution field is what they read.
     */
    @Transactional
    public void resolveDispute(Long actorId, Long disputeId, String resolutionType,
                               String resolution, String internalNote) {

        accessControl.require(actorId, Permissions.DISPUTE_MODERATE, ScopeType.PLATFORM, null);

        var rows = jdbc.queryForList(
                "select status from dispute where id = ?", String.class, disputeId);
        if (rows.isEmpty()) {
            throw new NotFoundException("Dispute", disputeId);
        }
        String current = rows.get(0);
        if ("RESOLVED".equals(current) || "REJECTED".equals(current)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This dispute is already " + current + ".");
        }

        jdbc.update("""
                update dispute
                   set status = 'RESOLVED', resolution_type = ?, resolution = ?,
                       resolved_by = ?, resolved_at = now(6),
                       version = version + 1, updated_at = now(6)
                 where id = ?
                """, resolutionType, resolution, actorId, disputeId);

        if (internalNote != null && !internalNote.isBlank()) {
            jdbc.update("""
                    insert into dispute_message (dispute_id, author_side, author_id, message,
                                                 internal, created_at)
                    values (?, 'OPERATIONS', ?, ?, 1, now(6))
                    """, disputeId, actorId, internalNote);
        }

        auditService.record(actorId, null, "DISPUTE_RESOLVED_BY_OPERATIONS", "DISPUTE",
                disputeId, current, "RESOLVED", resolutionType, "ADMIN");

        outbox.publish("DisputeResolved", "DISPUTE", disputeId,
                Map.of("resolutionType", resolutionType == null ? "" : resolutionType),
                actorId);
    }
}
