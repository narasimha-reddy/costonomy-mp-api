package com.costonomy.mp.credit.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * The credit module's clock, in India time (D-118). Named, not the unqualified {@code Clock}, so it can
 * never be picked up by code that wants some other clock.
 */
@Configuration
public class CreditClockConfig {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    @Bean(name = "creditClock")
    public Clock creditClock() {
        return Clock.system(ZONE);
    }
}
