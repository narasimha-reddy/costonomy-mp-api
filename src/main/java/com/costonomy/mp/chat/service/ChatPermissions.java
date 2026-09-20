package com.costonomy.mp.chat.service;

/**
 * The four grants chat needs, seeded by {@code V33__chat.sql}.
 *
 * <p>Read and write are separate on both sides — D-046, so support can be given
 * the whole read surface and none of the writes. The two sides are separate
 * codes rather than one, because a permission carries a scope: a restaurant's is
 * held on an outlet and a supplier's on a store, and one code held at both would
 * make "can this person write here" a question with two different answers.
 */
final class ChatPermissions {

    private ChatPermissions() {
    }

    static final String VIEW_RESTAURANT = "CHAT_VIEW";
    static final String SEND_RESTAURANT = "CHAT_SEND";
    static final String VIEW_SUPPLIER = "CHAT_VIEW_SUPPLIER";
    static final String SEND_SUPPLIER = "CHAT_SEND_SUPPLIER";
}
