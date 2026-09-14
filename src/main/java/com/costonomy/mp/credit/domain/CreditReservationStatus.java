package com.costonomy.mp.credit.domain;

/**
 * Doc 03 §9: {@code REQUESTED → RESERVED → UTILIZED}, with {@code REQUESTED →
 * FAILED} and {@code RESERVED → RELEASED | EXPIRED}.
 *
 * <p>Mirrors what happens to the order it is tied to (doc 01 §19): placed →
 * reserved, accepted → utilized, rejected or timed out → released. A partial
 * acceptance utilizes the accepted value and releases the rest, which is why
 * {@link #UTILIZED} does not imply the whole reservation was drawn.
 */
public enum CreditReservationStatus {

    REQUESTED,
    RESERVED,
    UTILIZED,
    RELEASED,
    EXPIRED,
    FAILED;

    public boolean holdsExposure() {
        return this == RESERVED;
    }
}
