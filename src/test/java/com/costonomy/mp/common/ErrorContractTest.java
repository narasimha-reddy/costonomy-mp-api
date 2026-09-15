package com.costonomy.mp.common;

import com.costonomy.mp.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The error contract. Doc 04 §22.
 *
 * <p>Error codes are the part of an API a client writes a switch statement
 * against. A renamed code, a status quietly changed from 409 to 422, or a
 * deleted one is a breaking change that compiles cleanly on both sides — which is
 * precisely why the documented contract deserves a test rather than a convention.
 */
class ErrorContractTest {

    @Nested
    @DisplayName("the documented catalogue")
    class Catalogue {

        @Test
        @DisplayName("every code doc 04 §22 names exists")
        void minimumCodesArePresent() {
            for (String code : List.of(
                    "VALIDATION_ERROR", "FORBIDDEN", "RESOURCE_NOT_FOUND",
                    "INVALID_STATE_TRANSITION", "SUPPLIER_ORDER_EXPIRED",
                    "SUPPLIER_ORDER_ALREADY_ACCEPTED", "PRICE_CHANGED", "SKU_UNAVAILABLE",
                    "SUPPLIER_OFFLINE", "CREDIT_LIMIT_EXCEEDED", "CREDIT_SUSPENDED",
                    "PAYMENT_FAILED", "PAYMENT_STATE_CONFLICT", "REFUND_ALREADY_REQUESTED",
                    "DELIVERY_UNAVAILABLE", "DELIVERY_REASSIGNMENT_FAILED",
                    "IDEMPOTENCY_KEY_REUSE", "CONCURRENT_MODIFICATION")) {

                assertThat(java.util.Arrays.stream(ErrorCode.values())
                        .anyMatch(value -> value.name().equals(code)))
                        .describedAs("doc 04 §22 requires %s", code)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("each code carries the status doc 04 §22 assigns it")
        void statusesMatchTheGuidance() {
            // The HTTP guidance is what a client's retry logic keys on: a 409 is
            // worth retrying after a refresh, a 422 is not, and a 429 means wait.
            // Swapping two of these silently changes how every client behaves.
            Map<ErrorCode, HttpStatus> documented = Map.ofEntries(
                    Map.entry(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST),
                    Map.entry(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN),
                    Map.entry(ErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND),
                    Map.entry(ErrorCode.INVALID_STATE_TRANSITION, HttpStatus.CONFLICT),
                    Map.entry(ErrorCode.CONCURRENT_MODIFICATION, HttpStatus.CONFLICT),
                    Map.entry(ErrorCode.IDEMPOTENCY_KEY_REUSE, HttpStatus.CONFLICT),
                    Map.entry(ErrorCode.SUPPLIER_ORDER_EXPIRED, HttpStatus.CONFLICT),
                    Map.entry(ErrorCode.SUPPLIER_ORDER_ALREADY_ACCEPTED, HttpStatus.CONFLICT),
                    Map.entry(ErrorCode.PAYMENT_STATE_CONFLICT, HttpStatus.CONFLICT),
                    Map.entry(ErrorCode.REFUND_ALREADY_REQUESTED, HttpStatus.CONFLICT),
                    Map.entry(ErrorCode.PRICE_CHANGED, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.SKU_UNAVAILABLE, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.SUPPLIER_OFFLINE, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.CREDIT_LIMIT_EXCEEDED, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.CREDIT_SUSPENDED, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.PAYMENT_FAILED, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.DELIVERY_UNAVAILABLE, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.DELIVERY_REASSIGNMENT_FAILED,
                            HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS));

            documented.forEach((code, status) ->
                    assertThat(code.status())
                            .describedAs("%s maps to %s", code, status)
                            .isEqualTo(status));
        }

        @Test
        @DisplayName("every code has a message a person could act on")
        void messagesAreUsable() {
            for (ErrorCode code : ErrorCode.values()) {
                assertThat(code.defaultMessage())
                        .describedAs("%s has a message", code)
                        .isNotBlank();
                // The message reaches a restaurant owner's phone. A code name
                // shouted back at them is not an error message.
                assertThat(code.defaultMessage())
                        .describedAs("%s does not just repeat its code", code)
                        .isNotEqualTo(code.name());
            }
        }

        @Test
        @DisplayName("no code returns a 200")
        void errorsAreNotSuccesses() {
            for (ErrorCode code : ErrorCode.values()) {
                assertThat(code.status().isError())
                        .describedAs("%s is an error status", code)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("a message never leaks an internal detail")
        void messagesAreSafe() {
            // Doc 09 §16 and §4. These messages are returned to clients verbatim,
            // so a stack trace, a table name or a provider's wording in one would
            // be a disclosure that no amount of log redaction catches.
            for (ErrorCode code : ErrorCode.values()) {
                String message = code.defaultMessage().toLowerCase(java.util.Locale.ROOT);
                assertThat(message)
                        .describedAs("%s", code)
                        .doesNotContain("exception")
                        .doesNotContain("sql")
                        .doesNotContain("null pointer")
                        .doesNotContain("com.costonomy");
            }
        }
    }
}
