package com.costonomy.mp.trust.domain;

/** Doc 03 §11: {@code PENDING → RECEIVED}. */
public enum ReceivingStatus {

    /** Goods delivered, nobody has checked them in yet. */
    PENDING,
    RECEIVED
}
