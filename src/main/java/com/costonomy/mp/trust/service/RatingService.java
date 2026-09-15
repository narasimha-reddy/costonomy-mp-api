package com.costonomy.mp.trust.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.trust.domain.Rating;
import com.costonomy.mp.trust.domain.RatingModerationStatus;
import com.costonomy.mp.trust.repository.RatingRepository;
import com.costonomy.mp.trust.web.dto.TrustDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Ratings. Doc 01 §24, doc 04 §17, doc 09 §9, §23A.23.
 *
 * <p><b>One per completed order, and only by the restaurant that placed it.</b>
 * Ratings are the marketplace's memory, and a rating from someone who did not buy
 * is not a memory of anything. The unique constraint is what actually prevents the
 * duplicate §23A.23 asks about, because a double tap is how it happens.
 *
 * <p><b>Published on write, hidden by moderation.</b> Doc 01 §24 says public
 * subject to moderation; pre-moderation would mean nothing appears until someone
 * reviews it, penalising a supplier for their reviewer's backlog. Hiding is
 * auditable and removes the rating from the average and from ranking, which is
 * what makes it worth doing.
 *
 * <p><b>An absent rating is absent.</b> A store nobody has rated has no average —
 * not three out of five. Doc 07 §4, and the same rule {@code BestValueScorer}
 * already applies to every other signal.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RatingService {

    private final RatingRepository ratings;
    private final TrustDirectory directory;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final OutboxService outbox;

    @Transactional
    public TrustDtos.RatingResponse rate(Long actorId, Long supplierOrderId,
                                         TrustDtos.CreateRatingRequest request) {

        var order = directory.order(supplierOrderId);
        if (order == null) {
            throw new NotFoundException("SupplierOrder", supplierOrderId);
        }
        accessControl.requireScoped(actorId, Permissions.RATING_CREATE,
                ScopeType.OUTLET, order.outletId(), "SupplierOrder");

        var existing = ratings.findBySupplierOrderId(supplierOrderId).orElse(null);
        if (existing != null) {
            // §23A.23. Returning the original rather than erroring: the honest
            // answer to "did my rating go through?" is the rating.
            return toResponse(existing);
        }

        if (!"COMPLETED".equals(order.status())) {
            // "After completion" (§23A.23). Rating an order still in flight rates
            // something that has not finished happening.
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "You can rate this order once you've received it.");
        }

        var rating = new Rating();
        rating.setSupplierOrderId(supplierOrderId);
        rating.setOutletId(order.outletId());
        rating.setSupplierStoreId(order.supplierStoreId());
        rating.setOverallRating(request.overall());
        rating.setProductQualityRating(request.productQuality());
        rating.setQuantityAccuracyRating(request.quantityAccuracy());
        rating.setPackagingRating(request.packaging());
        rating.setDeliveryRating(request.delivery());
        rating.setComment(request.comment());
        rating.setRatedBy(actorId);
        ratings.save(rating);

        auditService.record(actorId, null, "RATING_SUBMITTED", "RATING", rating.getId(),
                null, String.valueOf(request.overall()),
                "Order " + order.orderNumber(), "API");

        outbox.publish("RatingSubmitted", "RATING", rating.getId(),
                Map.of("outletId", order.outletId(),
                        "supplierStoreId", order.supplierStoreId(),
                        "supplierOrderId", supplierOrderId,
                        "overall", request.overall()),
                actorId);

        return toResponse(rating);
    }

    /** Hide a rating, or put it back. Doc 09 §9: all moderation is auditable. */
    @Transactional
    public TrustDtos.RatingResponse moderate(Long actorId, Long ratingId,
                                             TrustDtos.ModerateRatingRequest request) {

        accessControl.require(actorId, Permissions.RATING_MODERATE, ScopeType.PLATFORM, null);

        var rating = ratings.findById(ratingId)
                .orElseThrow(() -> new NotFoundException("Rating", ratingId));

        var previous = rating.getModerationStatus();
        rating.setModerationStatus(request.hide()
                ? RatingModerationStatus.HIDDEN : RatingModerationStatus.PUBLISHED);
        // Never nullable in practice: an unexplained removal is indistinguishable
        // from censorship, and the supplier it concerns is entitled to the reason.
        rating.setModerationReason(request.reason());
        rating.setModeratedBy(actorId);
        rating.setModeratedAt(Instant.now());
        ratings.save(rating);

        auditService.record(actorId, null, "RATING_MODERATED", "RATING", ratingId,
                previous.name(), rating.getModerationStatus().name(),
                request.reason(), "API");

        return toResponse(rating);
    }

    /** A store's public rating. Readable by anyone signed in — it is public. */
    @Transactional(readOnly = true)
    public TrustDtos.RatingSummaryResponse summaryFor(Long supplierStoreId) {
        var visible = ratings.findBySupplierStoreIdAndModerationStatusOrderByCreatedAtDesc(
                supplierStoreId, RatingModerationStatus.PUBLISHED);

        return new TrustDtos.RatingSummaryResponse(
                supplierStoreId, visible.size(),
                average(visible, Rating::getOverallRating),
                average(visible, Rating::getProductQualityRating),
                average(visible, Rating::getQuantityAccuracyRating),
                average(visible, Rating::getPackagingRating),
                average(visible, Rating::getDeliveryRating),
                // The most recent handful. A full history belongs behind paging,
                // and a summary that returns ten thousand rows is not a summary.
                visible.stream().limit(20).map(this::toResponse).toList());
    }

    @Transactional(readOnly = true)
    public TrustDtos.RatingResponse forOrder(Long actorId, Long supplierOrderId) {
        var order = directory.order(supplierOrderId);
        if (order == null) {
            throw new NotFoundException("SupplierOrder", supplierOrderId);
        }
        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW,
                ScopeType.OUTLET, order.outletId(), "SupplierOrder");

        return ratings.findBySupplierOrderId(supplierOrderId)
                .map(this::toResponse)
                .orElseThrow(() -> new NotFoundException("Rating", supplierOrderId));
    }

    /**
     * Mean of a dimension, over the ratings that gave one.
     *
     * @return null when nobody answered this dimension. Treating a blank as a
     *         three would let one half-filled form drag a store's packaging score
     *         toward the middle without anyone having said anything about packaging.
     */
    private BigDecimal average(List<Rating> ratings, Function<Rating, Integer> dimension) {
        var values = ratings.stream()
                .map(dimension)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (values.isEmpty()) {
            return null;
        }
        int total = values.stream().mapToInt(Integer::intValue).sum();
        return BigDecimal.valueOf(total)
                .divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

    private TrustDtos.RatingResponse toResponse(Rating rating) {
        return new TrustDtos.RatingResponse(
                rating.getId(), rating.getSupplierOrderId(), rating.getSupplierStoreId(),
                rating.getOverallRating(), rating.getProductQualityRating(),
                rating.getQuantityAccuracyRating(), rating.getPackagingRating(),
                rating.getDeliveryRating(), rating.getComment(),
                rating.getModerationStatus(), rating.getCreatedAt());
    }
}
