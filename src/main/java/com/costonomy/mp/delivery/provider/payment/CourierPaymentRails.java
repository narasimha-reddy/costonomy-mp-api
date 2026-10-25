package com.costonomy.mp.delivery.provider.payment;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The rails by code. A provider's {@code payment_rail} names one; an unknown name is a configuration error, not a default. */
@Component
public class CourierPaymentRails {

    private final Map<String, CourierPaymentRail> byCode;

    public CourierPaymentRails(List<CourierPaymentRail> rails) {
        this.byCode = rails.stream().collect(Collectors.toMap(CourierPaymentRail::code, Function.identity()));
    }

    public CourierPaymentRail require(String code) {
        var rail = byCode.get(code);
        if (rail == null) {
            throw new IllegalStateException("No courier payment rail named " + code);
        }
        return rail;
    }

    public boolean exists(String code) {
        return byCode.containsKey(code);
    }
}
