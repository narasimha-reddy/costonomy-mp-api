package com.costonomy.mp.trust.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * One restaurant's rating of one completed order. Doc 01 §24, §23A.23.
 *
 * <p>Overall is required; the four dimensions are optional, because a restaurant
 * in a hurry should be able to leave a rating rather than abandon a five-field
 * form — and a dimension left blank is genuinely absent rather than neutral, which
 * is why it is nullable rather than defaulted to three.
 */
@Entity
@Table(name = "rating")
@Getter
@Setter
@NoArgsConstructor
public class Rating extends BaseEntity {

    @Column(name = "supplier_order_id", nullable = false)
    private Long supplierOrderId;

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    @Column(name = "overall_rating", nullable = false)
    private Integer overallRating;

    @Column(name = "product_quality_rating")
    private Integer productQualityRating;

    @Column(name = "quantity_accuracy_rating")
    private Integer quantityAccuracyRating;

    @Column(name = "packaging_rating")
    private Integer packagingRating;

    @Column(name = "delivery_rating")
    private Integer deliveryRating;

    @Column(name = "comment", length = 2000)
    private String comment;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "moderation_status", nullable = false, length = 32)
    private RatingModerationStatus moderationStatus = RatingModerationStatus.PUBLISHED;

    @Column(name = "moderation_reason", length = 500)
    private String moderationReason;

    @Column(name = "moderated_by")
    private Long moderatedBy;

    @Column(name = "moderated_at")
    private Instant moderatedAt;

    @Column(name = "rated_by", nullable = false)
    private Long ratedBy;
}
