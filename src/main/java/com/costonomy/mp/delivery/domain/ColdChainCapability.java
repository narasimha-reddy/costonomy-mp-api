package com.costonomy.mp.delivery.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * A carrier's verified temperature-controlled vehicle class, with the evidence for it (D-134, D-121). The absence of
 * a row means the carrier is not known to carry chilled goods in that vehicle; nothing is assumed from the vehicle's
 * size.
 */
@Entity
@Table(name = "delivery_provider_cold_chain_capability")
@Getter
@Setter
@NoArgsConstructor
public class ColdChainCapability extends BaseEntity {

    @Column(name = "delivery_provider_id", nullable = false)
    private Long deliveryProviderId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "vehicle_type", nullable = false, length = 32)
    private VehicleType vehicleType;

    @Column(name = "evidence", nullable = false, length = 500)
    private String evidence;

    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt = Instant.now();

    @Column(name = "verified_by", length = 120)
    private String verifiedBy;
}
