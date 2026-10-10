package com.costonomy.mp.catalog.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.catalog.domain.SkuReview;
import com.costonomy.mp.catalog.repository.SkuReviewRepository;
import com.costonomy.mp.catalog.web.dto.CatalogDtos;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * What kitchens thought of a pack. D-096.
 *
 * <p><b>A review needs an order line, and the order has to have completed.</b>
 * That is the whole design: a review is a report of something that arrived, and
 * anything weaker is an opinion a competitor or the supplier themselves could
 * have written. It also means the reviewer is telling the truth about having
 * bought it, without anybody having to check.
 *
 * <p>One per line, so the same restaurant reviews again by buying again —
 * which is the right cadence: a pack that was good in March and wet in June has
 * two things worth saying.
 *
 * <p>Separate from the order rating, which is about the store and the delivery.
 * "It arrived late" and "the paneer was wet" are different complaints, and a
 * kitchen comparing packs needs the second.
 */
@Service
@RequiredArgsConstructor
public class SkuReviewService {

    private final SkuReviewRepository reviews;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;

    /**
     * Review a pack you received.
     *
     * <p>Returns the existing review rather than erroring when the line has
     * already been reviewed — §23A.23's rule for order ratings, and the honest
     * answer to "did that go through?" is the review.
     */
    @Transactional
    public CatalogDtos.SkuReviewResponse review(
            Long actorId, Long orderItemId, CatalogDtos.CreateSkuReviewRequest request) {

        var line = lineOf(orderItemId);

        accessControl.requireScoped(actorId, Permissions.RATING_CREATE,
                ScopeType.OUTLET, line.outletId(), "SupplierOrderItem");

        var existing = reviews.findBySupplierOrderItemId(orderItemId).orElse(null);
        if (existing != null) {
            return toResponse(existing);
        }

        if (!"COMPLETED".equals(line.orderStatus())) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "You can review this once the order is complete.");
        }
        if (request.rating() == null || request.rating() < 1 || request.rating() > 5) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "A rating is between 1 and 5.");
        }

        var review = new SkuReview();
        review.setSupplierSkuId(line.supplierSkuId());
        review.setSupplierOrderId(line.supplierOrderId());
        review.setSupplierOrderItemId(orderItemId);
        review.setOutletId(line.outletId());
        review.setRating(request.rating());
        review.setComment(request.comment());
        review.setReviewedBy(actorId);
        // Published on write and removed by moderation, never gated by it —
        // D-037, unchanged: gating means nothing appears until somebody looks.
        review.setModerationStatus("PUBLISHED");
        reviews.save(review);

        auditService.record(actorId, null, "SKU_REVIEW_SUBMITTED", "SUPPLIER_SKU",
                line.supplierSkuId(), null, String.valueOf(request.rating()),
                "Order item " + orderItemId, "API");

        return toResponse(review);
    }

    /** A pack's published reviews. Public to anyone signed in, like a rating. */
    @Transactional(readOnly = true)
    public List<CatalogDtos.SkuReviewResponse> forSku(Long skuId) {
        return reviews.findBySupplierSkuIdAndModerationStatusOrderByCreatedAtDesc(
                        skuId, "PUBLISHED").stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * The order line, and enough of its order to decide.
     *
     * <p>Read here rather than through the order module's repositories: a module
     * talks to another through a service or reads what it needs, never by
     * reaching into its tables through JPA entities it does not own.
     */
    private Line lineOf(Long orderItemId) {
        var rows = jdbc.query("""
                select i.supplier_sku_id, i.supplier_order_id, o.outlet_id, o.status
                  from supplier_order_item i
                  join supplier_order o on o.id = i.supplier_order_id
                 where i.id = ?
                """,
                (rs, n) -> new Line(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4)),
                orderItemId);
        if (rows.isEmpty()) {
            throw new NotFoundException("SupplierOrderItem", orderItemId);
        }
        return rows.get(0);
    }

    private CatalogDtos.SkuReviewResponse toResponse(SkuReview review) {
        var names = jdbc.queryForList(
                "select name from outlet where id = ?", String.class, review.getOutletId());
        return new CatalogDtos.SkuReviewResponse(
                review.getId(), review.getSupplierSkuId(), review.getSupplierOrderItemId(),
                review.getRating(), review.getComment(),
                names.isEmpty() ? null : names.get(0),
                review.getCreatedAt());
    }

    private record Line(Long supplierSkuId, Long supplierOrderId,
                        Long outletId, String orderStatus) {
    }
}
