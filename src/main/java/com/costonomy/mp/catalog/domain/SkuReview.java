package com.costonomy.mp.catalog.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * What one kitchen thought of one pack. D-096.
 *
 * <p><b>Tied to an order line, and there has to be one.</b> A review is a
 * report of something that arrived; that is what makes it worth reading and
 * what stops it being a competitor or the supplier themselves. The same
 * restaurant reviews again by buying again.
 *
 * <p>Separate from {@code rating}, which is about the order and the store.
 * "The delivery was late" and "the paneer was wet" are different complaints,
 * and a kitchen comparing packs needs the second one.
 */
@Entity
@Table(name = "sku_review")
@Getter
@Setter
@NoArgsConstructor
public class SkuReview extends BaseEntity {

    @Column(name = "supplier_sku_id", nullable = false)
    private Long supplierSkuId;

    @Column(name = "supplier_order_id", nullable = false)
    private Long supplierOrderId;

    /** The proof. Unique, so one line is reviewed once. */
    @Column(name = "supplier_order_item_id", nullable = false)
    private Long supplierOrderItemId;

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "rating", nullable = false)
    private Integer rating;

    @Column(name = "comment", length = 2000)
    private String comment;

    /**
     * Published on write, removed by moderation. D-037, unchanged here.
     *
     * <p>Hiding one takes it out of the average as well as out of the list,
     * which is what makes moderation more than cosmetic.
     */
    @Column(name = "moderation_status", nullable = false, length = 32)
    private String moderationStatus = "PUBLISHED";

    @Column(name = "moderation_reason", length = 500)
    private String moderationReason;

    @Column(name = "moderated_by")
    private Long moderatedBy;

    @Column(name = "moderated_at")
    private Instant moderatedAt;

    @Column(name = "reviewed_by", nullable = false)
    private Long reviewedBy;
}
