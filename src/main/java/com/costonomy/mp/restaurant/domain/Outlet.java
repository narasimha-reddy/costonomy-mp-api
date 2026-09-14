package com.costonomy.mp.restaurant.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * The procurement boundary. Doc 01 §4, doc 02 §4.
 *
 * <p>All purchasing, receiving and policy evaluation happens against an outlet.
 * The address here is a commercial fact — serviceability and delivery quotes are
 * computed against it — so it is stored on the outlet rather than referenced from
 * a shared address table that a later edit would silently rewrite for past orders.
 */
@Entity
@Table(name = "outlet")
@Getter
@Setter
@NoArgsConstructor
public class Outlet extends BaseEntity {

    @Column(name = "restaurant_id", nullable = false)
    private Long restaurantId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "address_line1", nullable = false, length = 250)
    private String addressLine1;

    @Column(name = "address_line2", length = 250)
    private String addressLine2;

    @Column(name = "landmark", length = 250)
    private String landmark;

    @Column(name = "city", nullable = false, length = 120)
    private String city;

    @Column(name = "state", nullable = false, length = 120)
    private String state;

    @Column(name = "pincode", length = 16)
    private String pincode;

    /** DECIMAL, not double — coordinates are compared and indexed. */
    @Column(name = "latitude", precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "google_place_id", length = 255)
    private String googlePlaceId;

    @Column(name = "formatted_address", length = 500)
    private String formattedAddress;

    @Column(name = "contact_name", length = 150)
    private String contactName;

    @Column(name = "contact_phone", length = 32)
    private String contactPhone;

    @Column(name = "delivery_instructions", length = 1000)
    private String deliveryInstructions;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE";
}
