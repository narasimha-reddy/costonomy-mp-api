package com.costonomy.mp.chat.domain;

/**
 * What can be shared into a conversation.
 *
 * <p>A typed reference rather than a pasted link. The server can then check the
 * thing belongs to this pair before rendering it, and the app can open the right
 * screen for whichever side is reading — a supplier tapping an order goes to
 * their view of it, not the restaurant's.
 */
public enum ChatAttachmentType {
    /** An intent — a request for prices and availability. */
    REQUEST,
    /** A supplier order. */
    ORDER
}
