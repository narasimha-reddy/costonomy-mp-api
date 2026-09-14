package com.costonomy.mp.common.idempotency;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the idempotency protocol.
 *
 * <p>The database-level race (two concurrent claims, one unique-constraint
 * winner) is exercised against real MySQL in the integration suite. These cover
 * the decision logic around it, which is where the subtle mistakes live.
 */
class IdempotencyServiceTest {

    private IdempotencyStore store;
    private IdempotencyService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    record OrderRequest(Long supplierStoreId, int quantity) {
    }

    record OrderResponse(Long orderId, String status) {
    }

    @BeforeEach
    void setUp() {
        store = mock(IdempotencyStore.class);
        service = new IdempotencyService(store, objectMapper);
        // @Value is not processed outside a Spring context.
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "retention", Duration.ofHours(24));
    }

    @Nested
    @DisplayName("request hashing")
    class Hashing {

        @Test
        @DisplayName("identical payloads hash identically")
        void identicalPayloadsMatch() {
            assertThat(service.hash(new OrderRequest(55L, 20)))
                    .isEqualTo(service.hash(new OrderRequest(55L, 20)));
        }

        @Test
        @DisplayName("a changed quantity produces a different hash")
        void differentPayloadsDiffer() {
            // This is what stops a client reusing a key for a genuinely different
            // order and being told the *first* order succeeded.
            assertThat(service.hash(new OrderRequest(55L, 20)))
                    .isNotEqualTo(service.hash(new OrderRequest(55L, 21)));
        }

        @Test
        @DisplayName("a null payload hashes without throwing")
        void nullPayloadIsSafe() {
            assertThat(service.hash(null)).hasSize(64);
        }

        @Test
        @DisplayName("hashes are SHA-256 hex")
        void hashIsHex() {
            assertThat(service.hash(new OrderRequest(1L, 1)))
                    .hasSize(64)
                    .matches("[0-9a-f]{64}");
        }
    }

    @Nested
    @DisplayName("execute")
    class Execute {

        @Test
        @DisplayName("runs the operation once when the key is unclaimed")
        void runsWhenClaimWon() {
            when(store.claim(any(), anyString(), anyString(), anyString(), any()))
                    .thenReturn(Optional.empty());
            var calls = new AtomicInteger();

            var result = service.execute(1L, "order.submit", "key-1",
                    new OrderRequest(55L, 20), OrderResponse.class,
                    () -> {
                        calls.incrementAndGet();
                        return new OrderResponse(900L, "PENDING_ACCEPTANCE");
                    });

            assertThat(calls.get()).isEqualTo(1);
            assertThat(result.orderId()).isEqualTo(900L);
            verify(store).complete(eq(1L), eq("order.submit"), eq("key-1"), any());
        }

        @Test
        @DisplayName("replays the stored response instead of re-running")
        void replaysCompleted() throws Exception {
            var request = new OrderRequest(55L, 20);
            var stored = completed(service.hash(request),
                    objectMapper.writeValueAsString(new OrderResponse(900L, "CONFIRMED")));
            when(store.claim(any(), anyString(), anyString(), anyString(), any()))
                    .thenReturn(Optional.of(stored));
            var calls = new AtomicInteger();

            var result = service.execute(1L, "order.submit", "key-1", request,
                    OrderResponse.class,
                    () -> {
                        calls.incrementAndGet();
                        return new OrderResponse(999L, "NEW");
                    });

            // The critical assertion: a retry of a completed submission must not
            // create a second supplier order.
            assertThat(calls.get()).describedAs("operation must not re-run").isZero();
            assertThat(result.orderId()).isEqualTo(900L);
        }

        @Test
        @DisplayName("rejects the same key carrying a different payload")
        void rejectsKeyReuse() {
            var stored = completed(service.hash(new OrderRequest(55L, 20)), "{}");
            when(store.claim(any(), anyString(), anyString(), anyString(), any()))
                    .thenReturn(Optional.of(stored));

            assertThatThrownBy(() -> service.execute(1L, "order.submit", "key-1",
                    // Same key, 30 units instead of 20.
                    new OrderRequest(55L, 30), OrderResponse.class,
                    () -> new OrderResponse(1L, "X")))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).code())
                    .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSE);
        }

        @Test
        @DisplayName("tells a concurrent retry to wait rather than starting a second attempt")
        void rejectsWhileInProgress() {
            var request = new OrderRequest(55L, 20);
            var inProgress = new IdempotencyRecord();
            inProgress.setRequestHash(service.hash(request));
            inProgress.setState(IdempotencyRecord.State.IN_PROGRESS);
            when(store.claim(any(), anyString(), anyString(), anyString(), any()))
                    .thenReturn(Optional.of(inProgress));

            // The real-world case: a mobile client times out at 10s and retries
            // while the first payment authorisation is still in flight.
            assertThatThrownBy(() -> service.execute(1L, "payment.create", "key-1",
                    request, OrderResponse.class, () -> new OrderResponse(1L, "X")))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).code())
                    .isEqualTo(ErrorCode.IDEMPOTENT_REQUEST_IN_PROGRESS);
        }

        @Test
        @DisplayName("releases the key when the operation throws, so a retry can run")
        void releasesKeyOnFailure() {
            when(store.claim(any(), anyString(), anyString(), anyString(), any()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.execute(1L, "order.submit", "key-1",
                    new OrderRequest(55L, 20), OrderResponse.class,
                    () -> {
                        throw new BusinessException(ErrorCode.SKU_UNAVAILABLE);
                    }))
                    .isInstanceOf(BusinessException.class);

            verify(store).fail(1L, "order.submit", "key-1");
            // Storing a failure as the replayable response would make the error
            // permanent for that key.
            verify(store, never()).complete(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("propagates the original failure, not an idempotency error")
        void propagatesOriginalFailure() {
            when(store.claim(any(), anyString(), anyString(), anyString(), any()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.execute(1L, "order.submit", "key-1",
                    new OrderRequest(55L, 20), OrderResponse.class,
                    () -> {
                        throw new BusinessException(ErrorCode.CREDIT_LIMIT_EXCEEDED);
                    }))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).code())
                    // The client must learn their credit limit was exceeded, not
                    // that something went wrong with idempotency bookkeeping.
                    .isEqualTo(ErrorCode.CREDIT_LIMIT_EXCEEDED);
        }
    }

    private static IdempotencyRecord completed(String hash, String body) {
        var record = new IdempotencyRecord();
        record.setRequestHash(hash);
        record.setState(IdempotencyRecord.State.COMPLETED);
        record.setResponseBody(body);
        record.setResponseStatus(200);
        return record;
    }
}
