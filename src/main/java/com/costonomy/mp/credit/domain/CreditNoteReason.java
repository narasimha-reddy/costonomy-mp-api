package com.costonomy.mp.credit.domain;

/** Why a credit note was issued (plan S13). A write-off is recorded as OTHER with the supplier's reason as the note. */
public enum CreditNoteReason {
    SHORT_SUPPLY, QUALITY, PRICE, CANCELLED, GOODWILL, OTHER;

    public static final String ALL_PATTERN = "SHORT_SUPPLY|QUALITY|PRICE|CANCELLED|GOODWILL|OTHER";
}
