package com.costonomy.mp.delivery.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A provider Mandi can dispatch to, as a row rather than as configuration.
 *
 * <p>Named {@code ...Record} because {@code DeliveryProvider} is the port; this is
 * the registration of one. Enabling or disabling a courier is then an operational
 * change with an audit trail instead of a deploy.
 */
@Entity
@Table(name = "delivery_provider")
@Getter
@Setter
@NoArgsConstructor
public class DeliveryProviderRecord extends BaseEntity {

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "supports_tracking", nullable = false)
    private Boolean supportsTracking = true;

    @Column(name = "supports_proof", nullable = false)
    private Boolean supportsProof = false;

    /** Tie-break only; cost and ETA decide first (doc 06 §4). */
    @Column(name = "priority", nullable = false)
    private Integer priority = 100;
}
