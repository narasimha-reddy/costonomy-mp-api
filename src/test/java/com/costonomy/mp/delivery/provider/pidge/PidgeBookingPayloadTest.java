package com.costonomy.mp.delivery.provider.pidge;

import com.costonomy.mp.delivery.domain.VehicleType;
import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.provider.DeliveryProviderException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.client.MockServerRestTemplateCustomizer;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** What is sent to Pidge when booking, and that nothing in it or in the answer is invented. */
class PidgeBookingPayloadTest {

    private static final String BASE_URL = "https://pidge.example.invalid";
    private static final String ORDER_URL = BASE_URL + "/v1.0/store/channel/vendor/order";

    private MockRestServiceServer server;
    private PidgeApiClient client;

    @BeforeEach
    void setUp() {
        var properties = new PidgeProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setApiToken("test-token");
        properties.setTimeout(Duration.ofSeconds(5));
        properties.setRateLimitRps(1000);
        var customizer = new MockServerRestTemplateCustomizer();
        client = new PidgeApiClient(properties,
                new RestTemplateBuilder().additionalCustomizers(customizer), new ObjectMapper());
        server = customizer.getServer();
    }

    private DeliveryProvider.BookingRequest booking(String pickupPhone, String dropPhone, BigDecimal goodsValue) {
        return new DeliveryProvider.BookingRequest(
                101L, "q-9",
                "Indiranagar, Bengaluru", new BigDecimal("12.9716"), new BigDecimal("77.5946"),
                "Store Desk", pickupPhone,
                "Koramangala, Bengaluru", new BigDecimal("12.9352"), new BigDecimal("77.6245"),
                "Asha", dropPhone,
                "mp-delivery-1-1",
                BigDecimal.valueOf(2.5), BigDecimal.valueOf(0.01), VehicleType.TWO_WHEELER,
                new DeliveryProvider.Locality("Bengaluru", "Karnataka", "560038"),
                new DeliveryProvider.Locality("Bengaluru", "Karnataka", "560034"),
                goodsValue);
    }

    @Test
    @DisplayName("the address carries city, state and pincode, the phones are 10 digits, and the bill is the order's value")
    void payload() {
        server.expect(requestTo(ORDER_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.sender_detail.address.city").value("Bengaluru"))
                .andExpect(jsonPath("$.sender_detail.address.state").value("Karnataka"))
                .andExpect(jsonPath("$.sender_detail.address.pincode").value("560038"))
                .andExpect(jsonPath("$.trips[0].receiver_detail.address.city").value("Bengaluru"))
                .andExpect(jsonPath("$.trips[0].receiver_detail.address.pincode").value("560034"))
                .andExpect(jsonPath("$.sender_detail.mobile").value("9876500000"))
                .andExpect(jsonPath("$.trips[0].receiver_detail.mobile").value("9876511111"))
                .andExpect(jsonPath("$.trips[0].bill_amount").value(1486.8))
                .andRespond(withSuccess("{\"data\": {\"SO-101\": \"pidge-1\"}}", MediaType.APPLICATION_JSON));

        var booked = client.createOrder(booking("+919876500000", "09876511111", new BigDecimal("1486.80")));

        assertThat(booked.providerDeliveryId()).isEqualTo("pidge-1");
        // Pidge's answer states no fare or arrival time, so none is made up here.
        assertThat(booked.amount()).isNull();
        assertThat(booked.etaMinutes()).isNull();
        server.verify();
    }

    @Test
    @DisplayName("a missing or malformed phone refuses the booking by name, before any call")
    void phoneRequired() {
        assertThatThrownBy(() -> client.createOrder(booking(null, "9876511111", BigDecimal.TEN)))
                .isInstanceOf(DeliveryProviderException.class).hasMessageContaining("pickup contact phone");
        assertThatThrownBy(() -> client.createOrder(booking("12345", "9876511111", BigDecimal.TEN)))
                .isInstanceOf(DeliveryProviderException.class).hasMessageContaining("pickup contact phone");
        assertThatThrownBy(() -> client.createOrder(booking("9876500000", " ", BigDecimal.TEN)))
                .isInstanceOf(DeliveryProviderException.class).hasMessageContaining("drop contact phone");
        server.verify();
    }

    @Test
    @DisplayName("a booking with no order value is refused")
    void valueRequired() {
        assertThatThrownBy(() -> client.createOrder(booking("9876500000", "9876511111", null)))
                .isInstanceOf(DeliveryProviderException.class).hasMessageContaining("order value");
        server.verify();
    }

    @Test
    @DisplayName("Indian mobiles are reduced to their 10 digits")
    void indianMobile() {
        assertThat(PidgeApiClient.indianMobile("+91 98765 43210")).isEqualTo("9876543210");
        assertThat(PidgeApiClient.indianMobile("919876543210")).isEqualTo("9876543210");
        assertThat(PidgeApiClient.indianMobile("5876543210")).isNull();
        assertThat(PidgeApiClient.indianMobile("")).isNull();
    }

    /** A food delivery (D-197): no supplier order, our own reference instead. */
    private DeliveryProvider.BookingRequest foodBooking(String reference) {
        return new DeliveryProvider.BookingRequest(
                null, "q-9",
                "Indiranagar, Bengaluru", new BigDecimal("12.9716"), new BigDecimal("77.5946"),
                "Outlet Desk", "9876500000",
                "Koramangala, Bengaluru", new BigDecimal("12.9352"), new BigDecimal("77.6245"),
                "Asha", "9876511111",
                "mp-food-delivery-7-1",
                BigDecimal.valueOf(2.5), BigDecimal.valueOf(0.01), VehicleType.TWO_WHEELER,
                new DeliveryProvider.Locality("Bengaluru", "Karnataka", "560038"),
                new DeliveryProvider.Locality("Bengaluru", "Karnataka", "560034"),
                new BigDecimal("640.00"), reference);
    }

    @Test
    @DisplayName("a food delivery is booked under our reference, not an invented supplier order id")
    void foodDeliveryUsesReference() {
        server.expect(requestTo(ORDER_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.trips[0].source_order_id").value("FD-7"))
                .andExpect(jsonPath("$.trips[0].packages[0].label").value("Order FD-7"))
                .andRespond(withSuccess("{\"data\": {\"FD-7\": \"pidge-7\"}}", MediaType.APPLICATION_JSON));

        var booked = client.createOrder(foodBooking("FD-7"));

        assertThat(booked.providerDeliveryId()).isEqualTo("pidge-7");
        server.verify();
    }

    @Test
    @DisplayName("a supplier booking is still named SO-<id> with no reference")
    void supplierBookingUnchanged() {
        server.expect(requestTo(ORDER_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.trips[0].source_order_id").value("SO-101"))
                .andExpect(jsonPath("$.trips[0].packages[0].label").value("Order #101"))
                .andRespond(withSuccess("{\"data\": {\"SO-101\": \"pidge-1\"}}", MediaType.APPLICATION_JSON));

        client.createOrder(booking("9876500000", "9876511111", BigDecimal.TEN));
        server.verify();
    }

    @Test
    @DisplayName("a booking with neither a supplier order nor a reference is refused before any call")
    void neitherIdNorReferenceRefused() {
        assertThatThrownBy(() -> client.createOrder(foodBooking(null)))
                .isInstanceOf(DeliveryProviderException.class).hasMessageContaining("supplier order or reference");
        assertThatThrownBy(() -> client.createOrder(foodBooking("  ")))
                .isInstanceOf(DeliveryProviderException.class).hasMessageContaining("supplier order or reference");
        server.verify();
    }
}
