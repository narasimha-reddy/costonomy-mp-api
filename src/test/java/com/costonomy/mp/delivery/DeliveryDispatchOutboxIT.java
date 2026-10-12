package com.costonomy.mp.delivery;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.outbox.OutboxPublisher;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.delivery.service.DeliveryService;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.OutboxQuiet;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * What the relay does with the outcome of an auto-dispatch (D-194 follow-up): a permanent business refusal ends the
 * event; anything else is retried and the row shows the real cause.
 */
class DeliveryDispatchOutboxIT extends AbstractIntegrationTest {

    @MockBean private DeliveryService deliveryService;

    @Autowired private OutboxService outbox;
    @Autowired private OutboxPublisher publisher;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;

    @Autowired private LockProvider lockProvider;

    @BeforeEach
    void quiet() throws Exception {
        OutboxQuiet.awaitLockFree(lockProvider); // the context's start-up drain must not race the stubbing below
        reset(deliveryService);
        jdbc.update("update outbox_event set status = 'PUBLISHED', published_at = now(3) where status = 'PENDING'");
    }

    private long publishReady(long orderId) {
        new TransactionTemplate(txManager).executeWithoutResult(s ->
                outbox.publish("SupplierOrderReady", "SUPPLIER_ORDER", orderId, Map.of("k", "v"), null));
        return orderId;
    }

    private Map<String, Object> row(long orderId) {
        return jdbc.queryForMap("select status, attempt_count, last_error from outbox_event "
                + "where event_type = 'SupplierOrderReady' and aggregate_id = ?", orderId);
    }

    @Test
    void aPermanentBusinessRefusalIsSwallowedAndTheEventIsPublished() {
        long orderId = publishReady(910_000_001L);
        doThrow(new BusinessException(ErrorCode.DELIVERY_UNAVAILABLE, "This supplier has no delivery option configured."))
                .when(deliveryService).autoDispatch(anyLong());

        publisher.drainUnlocked();

        assertThat(row(orderId).get("status")).isEqualTo("PUBLISHED");
        assertThat(row(orderId).get("attempt_count")).isEqualTo(0);
    }

    @Test
    void anyOtherFailureIsRetriedAndLastErrorNamesTheRealCause() {
        long orderId = publishReady(910_000_002L);
        doThrow(new IllegalStateException("provider connection reset"))
                .when(deliveryService).autoDispatch(anyLong());

        publisher.drainUnlocked();

        var r = row(orderId);
        assertThat(r.get("status")).isEqualTo("PENDING");
        assertThat(r.get("attempt_count")).isEqualTo(1);
        assertThat(r.get("last_error").toString()).contains("provider connection reset")
                .doesNotContain("rollback-only");
    }
}
