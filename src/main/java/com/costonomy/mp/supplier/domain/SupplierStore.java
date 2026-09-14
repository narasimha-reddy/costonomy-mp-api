package com.costonomy.mp.supplier.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * The operational purchasing source. Doc 02 §4.
 *
 * <p>Store-level state is authoritative for availability, price, delivery and
 * credit: an organisation can be verified and active while one of its stores is
 * offline for the afternoon.
 */
@Entity
@Table(name = "supplier_store")
@Getter
@Setter
@NoArgsConstructor
public class SupplierStore extends BaseEntity {

    @Column(name = "supplier_organization_id", nullable = false)
    private Long supplierOrganizationId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "address_line1", nullable = false, length = 250)
    private String addressLine1;

    @Column(name = "address_line2", length = 250)
    private String addressLine2;

    @Column(name = "city", nullable = false, length = 120)
    private String city;

    @Column(name = "state", nullable = false, length = 120)
    private String state;

    @Column(name = "pincode", length = 16)
    private String pincode;

    @Column(name = "latitude", precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "google_place_id", length = 255)
    private String googlePlaceId;

    @Column(name = "contact_name", length = 150)
    private String contactName;

    @Column(name = "contact_phone", length = 32)
    private String contactPhone;

    @Column(name = "operating_hours_json", columnDefinition = "json")
    private String operatingHoursJson;

    /** ACTIVE, OFFLINE or SUSPENDED. Independent of the organisation's lifecycle. */
    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE";

    /**
     * Seconds the supplier has to respond to an order.
     *
     * <p>60 by default and configurable per store — doc 01 §12 rule 9 and doc 13
     * both make this non-negotiable. The order snapshots a deadline from this at
     * creation time; changing it later must not move a live order's deadline.
     */
    @Column(name = "response_sla_seconds", nullable = false)
    private Integer responseSlaSeconds = 60;

    /** Minutes from acceptance to ready-for-pickup. Feeds the ETA shown at ranking. */
    @Column(name = "preparation_minutes", nullable = false)
    private Integer preparationMinutes = 60;

    public boolean isOperational() {
        return "ACTIVE".equals(status);
    }
}
