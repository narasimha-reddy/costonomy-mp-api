package com.costonomy.mp.realtime.domain;

import java.util.Objects;

/**
 * Who an event is for. The unit of authorization on the socket.
 *
 * <p>A channel names a tenant scope — {@code outlet:12}, {@code supplier-store:7}
 * — and a session joins one only if its user holds a grant there. That is the
 * whole access model for realtime, and it is deliberately the same shape as the
 * scopes {@code AccessControlService} already checks: a second, parallel notion of
 * "who can see this" would drift from the first, and the drift would be invisible
 * until someone saw another restaurant's orders.
 *
 * <p>Deliberately <b>not</b> per-user. Two people at the same outlet watching the
 * same order should both see it move, and routing by user would mean the person
 * who did not place the order sees nothing.
 */
public record RealtimeChannel(Type type, Long id) {

    public enum Type {
        OUTLET("outlet"),
        SUPPLIER_STORE("supplier-store");

        private final String prefix;

        Type(String prefix) {
            this.prefix = prefix;
        }

        public String prefix() {
            return prefix;
        }
    }

    public RealtimeChannel {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
    }

    public static RealtimeChannel outlet(Long outletId) {
        return new RealtimeChannel(Type.OUTLET, outletId);
    }

    public static RealtimeChannel supplierStore(Long storeId) {
        return new RealtimeChannel(Type.SUPPLIER_STORE, storeId);
    }

    /** The wire form, which is what the database stores and the client sends. */
    public String name() {
        return type.prefix() + ":" + id;
    }

    /**
     * Parse a channel a client asked to join.
     *
     * @return null for anything unrecognised — an unknown channel is refused
     *         rather than guessed at, because a guess here is an authorization
     *         decision made on a malformed string
     */
    public static RealtimeChannel parse(String name) {
        if (name == null) {
            return null;
        }
        int separator = name.indexOf(':');
        if (separator <= 0 || separator == name.length() - 1) {
            return null;
        }
        String prefix = name.substring(0, separator);
        for (Type type : Type.values()) {
            if (type.prefix().equals(prefix)) {
                try {
                    return new RealtimeChannel(type, Long.parseLong(name.substring(separator + 1)));
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
        }
        return null;
    }
}
