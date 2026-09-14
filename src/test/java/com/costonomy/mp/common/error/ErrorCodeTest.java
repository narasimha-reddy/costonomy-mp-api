package com.costonomy.mp.common.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the properties of the error catalogue that clients depend on.
 *
 * <p>Error codes are part of the public API — the mobile client branches on
 * them — so these tests exist to make an accidental change visible.
 */
class ErrorCodeTest {

    @Test
    @DisplayName("state and concurrency conflicts are 409, per doc 04 §22")
    void conflictsUse409() {
        assertThat(ErrorCode.SUPPLIER_ORDER_EXPIRED.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.SUPPLIER_ORDER_ALREADY_ACCEPTED.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.CONCURRENT_MODIFICATION.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.IDEMPOTENCY_KEY_REUSE.status()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("business-rule violations are 422, not 400")
    void businessRulesUse422() {
        // A request that is well-formed but not permitted is not a malformed
        // request. Clients retry 400s differently from 422s.
        assertThat(ErrorCode.PRICE_CHANGED.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ErrorCode.CREDIT_LIMIT_EXCEEDED.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ErrorCode.SKU_UNAVAILABLE.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ErrorCode.SUPPLIER_OFFLINE.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("a scope violation is 403 and distinct from a plain FORBIDDEN")
    void scopeViolationIsSeparate() {
        // Doc 03 §16: holding a permission does not grant access to another
        // tenant's resource. Keeping the code separate makes that failure
        // distinguishable in logs and audits.
        assertThat(ErrorCode.RESOURCE_SCOPE_VIOLATION.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ErrorCode.RESOURCE_SCOPE_VIOLATION).isNotEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("every code has a user-safe default message")
    void everyCodeHasAMessage() {
        // The message is shown to a restaurant or supplier user, so it must read
        // as plain English and never leak internals.
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.defaultMessage())
                    .describedAs("%s needs a user-facing message", code)
                    .isNotBlank();
            assertThat(code.defaultMessage().toLowerCase())
                    .describedAs("%s message must not leak internals", code)
                    .doesNotContain("exception", "sql", "null pointer", "stacktrace");
        }
    }

    @Test
    @DisplayName("every code carries a status")
    void everyCodeHasAStatus() {
        assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::status))
                .allSatisfy(status -> assertThat(status).isNotNull());
    }
}
