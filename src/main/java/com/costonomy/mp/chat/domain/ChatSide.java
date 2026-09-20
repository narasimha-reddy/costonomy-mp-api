package com.costonomy.mp.chat.domain;

/**
 * Which end of a conversation somebody is on.
 *
 * <p>The side, not the person. A store is answered by whoever is on the counter
 * and a kitchen by whoever is ordering that day, so what a reader needs to know
 * is which organisation spoke — and an unread badge belongs to the side rather
 * than to three colleagues each ignoring it separately.
 */
public enum ChatSide {
    RESTAURANT,
    SUPPLIER;

    public ChatSide other() {
        return this == RESTAURANT ? SUPPLIER : RESTAURANT;
    }

    /**
     * The event raised when this side speaks. D-044: the name is owned by the
     * enum that raises it, never assembled inline.
     *
     * <p>Two names rather than one, because a notification rule maps an event to
     * <em>one</em> audience and a message has to reach whichever side did not
     * send it. One event with the audience decided at delivery time would put
     * that decision in the relay, where nobody looking at the rule catalogue
     * would find it.
     */
    public String messageEventName() {
        return this == RESTAURANT ? "ChatMessageToSupplier" : "ChatMessageToRestaurant";
    }
}
