package com.costonomy.mp.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * The response envelope every endpoint returns.
 *
 * <p>Shape is fixed by {@code docs/specs/04-api-specification.md} §2:
 *
 * <pre>
 * { "data": {...}, "error": null, "meta": {} }
 * { "data": null,  "error": {...}, "meta": { "requestId": "..." } }
 * </pre>
 *
 * <p>{@code data} and {@code error} are mutually exclusive — exactly one is
 * non-null. Clients can branch on {@code error} alone without also inspecting
 * the HTTP status, which matters because the mobile client maps errors centrally
 * (PRD §23A.51).
 *
 * <p>All three fields are always serialised, including nulls. A client that has
 * to distinguish "absent" from "null" is a client that will get it wrong, so the
 * envelope is deliberately not {@code @JsonInclude(NON_NULL)} at the top level.
 */
public record ApiResponse<T>(T data, ApiError error, Map<String, Object> meta) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data, null, Map.of());
    }

    public static <T> ApiResponse<T> ok(T data, Map<String, Object> meta) {
        return new ApiResponse<>(data, null, meta == null ? Map.of() : meta);
    }

    /**
     * A paginated response. Cursor-based, per doc 04 §20 — offset pagination
     * over a feed that is being written to concurrently silently skips and
     * repeats rows.
     */
    public static <T> ApiResponse<T> page(T data, String nextCursor, boolean hasMore) {
        // HashMap rather than Map.of: nextCursor is null on the last page, and
        // Map.of rejects null values. The client needs to see the key.
        var meta = new java.util.HashMap<String, Object>();
        meta.put("nextCursor", nextCursor);
        meta.put("hasMore", hasMore);
        return new ApiResponse<>(data, null, meta);
    }

    public static <T> ApiResponse<T> failure(ApiError error, String requestId) {
        var meta = new java.util.HashMap<String, Object>();
        meta.put("requestId", requestId);
        return new ApiResponse<>(null, error, meta);
    }

    /**
     * The error half of the envelope.
     *
     * <p>{@code details} carries structured, machine-readable context — field
     * violations, the new price on a {@code PRICE_CHANGED}, the available credit
     * on a {@code CREDIT_LIMIT_EXCEEDED}. It is omitted when empty.
     *
     * <p>{@code message} is shown to a user, so it must be plain and actionable.
     * It must never contain a stack trace, a SQL fragment, a provider payload or
     * anything about our internals (doc 04 §2, doc 09 §16).
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ApiError(String code, String message, Map<String, Object> details) {

        public ApiError(String code, String message) {
            this(code, message, Map.of());
        }
    }
}
