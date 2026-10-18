package com.costonomy.mp.delivery.provider;

import com.costonomy.mp.delivery.domain.VehicleType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** How a consignment is named to a carrier: a supplier order as before, a food delivery by our own reference (D-201). */
class DeliveryProviderRequestTest {

    private static DeliveryProvider.BookingRequest booking(Long supplierOrderId, String reference) {
        return new DeliveryProvider.BookingRequest(supplierOrderId, "q", "a", BigDecimal.ONE, BigDecimal.ONE, "n", "p",
                "b", BigDecimal.ONE, BigDecimal.ONE, "m", "p", "key", BigDecimal.ONE, BigDecimal.ONE,
                VehicleType.TWO_WHEELER, null, null, BigDecimal.TEN, reference);
    }

    @Test
    @DisplayName("sourceRef is the reference when there is one, otherwise SO-<id>, otherwise null")
    void sourceRef() {
        assertThat(booking(101L, null).sourceRef()).isEqualTo("SO-101");
        assertThat(booking(null, "FD-7").sourceRef()).isEqualTo("FD-7");
        assertThat(booking(101L, "FD-7").sourceRef()).as("an explicit reference wins").isEqualTo("FD-7");
        assertThat(booking(null, null).sourceRef()).isNull();
        assertThat(booking(null, "  ").sourceRef()).as("blank is no reference").isNull();
    }

    @Test
    @DisplayName("the old constructors leave reference null, so supplier callers are unchanged")
    void oldConstructorsLeaveReferenceNull() {
        var request = new DeliveryProvider.BookingRequest(101L, "q", "a", BigDecimal.ONE, BigDecimal.ONE, "n", "p",
                "b", BigDecimal.ONE, BigDecimal.ONE, "m", "p", "key", BigDecimal.ONE, BigDecimal.ONE,
                VehicleType.TWO_WHEELER, null, null, BigDecimal.TEN);
        assertThat(request.reference()).isNull();
        assertThat(request.sourceRef()).isEqualTo("SO-101");

        var quote = new DeliveryProvider.QuoteRequest(101L, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.TEN, 30);
        assertThat(quote.reference()).isNull();
    }
}
