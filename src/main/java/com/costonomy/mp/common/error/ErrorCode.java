package com.costonomy.mp.common.error;

import org.springframework.http.HttpStatus;

/**
 * The API's error vocabulary.
 *
 * <p>Every code in {@code docs/specs/04-api-specification.md} §22 appears here,
 * plus the ones the domain needs. Codes are stable strings and part of the
 * public contract: the mobile client branches on them. **Never rename or
 * repurpose one** — add a new code instead.
 *
 * <p>Each code carries its HTTP status so the mapping lives in one place rather
 * than being re-decided in each handler. The status guidance is doc 04 §22:
 * 409 for state and concurrency conflicts, 422 for business-rule violations,
 * 400 only for a malformed request.
 *
 * <p>The default {@code message} is a safe, user-facing fallback. A service
 * throwing the error can supply something more specific, but must keep it free
 * of internals.
 */
public enum ErrorCode {

    // ── Request / generic (400, 404) ─────────────────────────────────────
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST,
            "Some of the information provided isn't valid."),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST,
            "We couldn't read that request."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND,
            "We couldn't find what you're looking for."),

    // ── Authentication / authorization (401, 403) ────────────────────────
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED,
            "Please sign in to continue."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED,
            "Your session has expired. Please sign in again."),
    FORBIDDEN(HttpStatus.FORBIDDEN,
            "You don't have permission to do that."),
    /**
     * The actor holds the permission but the resource belongs to a different
     * restaurant, outlet, supplier or store. Kept separate from FORBIDDEN so
     * tenant-isolation failures are distinguishable in logs and audits
     * (doc 03 §16, doc 09 §3).
     */
    RESOURCE_SCOPE_VIOLATION(HttpStatus.FORBIDDEN,
            "You don't have access to that."),

    // ── OTP / auth flow (422, 429) ───────────────────────────────────────
    OTP_INVALID(HttpStatus.UNPROCESSABLE_ENTITY,
            "That code isn't correct."),
    OTP_EXPIRED(HttpStatus.UNPROCESSABLE_ENTITY,
            "That code has expired. Request a new one."),
    OTP_ATTEMPTS_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS,
            "Too many incorrect attempts. Try again in a few minutes."),
    OTP_RESEND_TOO_SOON(HttpStatus.TOO_MANY_REQUESTS,
            "Please wait before requesting another code."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS,
            "Too many requests. Please try again shortly."),

    // ── State machine / concurrency (409) ────────────────────────────────
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT,
            "That action isn't available right now."),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT,
            "Someone else just changed this. Please refresh and try again."),
    IDEMPOTENCY_KEY_REUSE(HttpStatus.CONFLICT,
            "This request was already made with different details."),
    IDEMPOTENT_REQUEST_IN_PROGRESS(HttpStatus.CONFLICT,
            "That request is still being processed."),

    // ── Requests (409) ───────────────────────────────────────────────────
    //
    // Its own code rather than SUPPLIER_ORDER_EXPIRED, which is what it would
    // otherwise be borrowing. A client matching on codes to decide what to show
    // would tell a supplier their *order* expired when what lapsed was a request
    // they had not answered yet — and D-018 exists to stop exactly that kind of
    // accurate-but-useless report.
    INTENT_EXPIRED(HttpStatus.CONFLICT,
            "This request can no longer be answered."),

    // ── Supplier orders (409, 422) ───────────────────────────────────────
    SUPPLIER_ORDER_EXPIRED(HttpStatus.CONFLICT,
            "This order can no longer be accepted."),
    SUPPLIER_ORDER_ALREADY_ACCEPTED(HttpStatus.CONFLICT,
            "This order has already been accepted."),
    SUPPLIER_ORDER_ALREADY_RESOLVED(HttpStatus.CONFLICT,
            "This order has already been responded to."),
    ACCEPTED_QUANTITY_EXCEEDS_REQUESTED(HttpStatus.UNPROCESSABLE_ENTITY,
            "You can't accept more than was ordered."),

    // ── Catalog / pricing (422) ──────────────────────────────────────────
    PRICE_CHANGED(HttpStatus.UNPROCESSABLE_ENTITY,
            "The price has changed since you added this. Please review."),
    SKU_UNAVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY,
            "That product is no longer available."),
    SUPPLIER_OFFLINE(HttpStatus.UNPROCESSABLE_ENTITY,
            "This supplier isn't taking orders right now."),
    SUPPLIER_NOT_SERVICEABLE(HttpStatus.UNPROCESSABLE_ENTITY,
            "This supplier doesn't deliver to your outlet."),
    DUPLICATE_SKU_CODE(HttpStatus.CONFLICT,
            "A product with that SKU code already exists in this store."),

    // ── Credit (422) ─────────────────────────────────────────────────────
    CREDIT_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY,
            "This order exceeds your available credit with this supplier."),
    CREDIT_SUSPENDED(HttpStatus.UNPROCESSABLE_ENTITY,
            "Credit with this supplier is currently suspended."),
    CREDIT_AGREEMENT_NOT_ACTIVE(HttpStatus.UNPROCESSABLE_ENTITY,
            "You don't have an active credit agreement with this supplier."),
    CREDIT_SINGLE_ORDER_CAP_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY,
            "This order is larger than the per-order credit limit."),

    // ── Payments (422, 409) ──────────────────────────────────────────────
    PAYMENT_FAILED(HttpStatus.UNPROCESSABLE_ENTITY,
            "The payment didn't go through."),
    PAYMENT_STATE_CONFLICT(HttpStatus.CONFLICT,
            "This payment has already moved on."),
    REFUND_ALREADY_REQUESTED(HttpStatus.CONFLICT,
            "A refund has already been requested for this."),
    WEBHOOK_SIGNATURE_INVALID(HttpStatus.BAD_REQUEST,
            "Invalid webhook signature."),

    // ── Delivery (422) ───────────────────────────────────────────────────
    DELIVERY_UNAVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY,
            "No delivery partner is available right now."),
    DELIVERY_REASSIGNMENT_FAILED(HttpStatus.UNPROCESSABLE_ENTITY,
            "We couldn't find another delivery partner."),

    // ── Approval (422) ───────────────────────────────────────────────────
    APPROVAL_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY,
            "This order needs approval before it can be placed."),
    APPROVAL_NOT_PENDING(HttpStatus.CONFLICT,
            "This approval has already been decided."),

    // ── Catalog import (422) ─────────────────────────────────────────────
    IMPORT_VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY,
            "Some rows in that file couldn't be imported."),

    // ── Unexpected (500) ─────────────────────────────────────────────────
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR,
            "Something went wrong on our side. Please try again."),
    PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE,
            "A service we depend on isn't responding. Please try again shortly.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
