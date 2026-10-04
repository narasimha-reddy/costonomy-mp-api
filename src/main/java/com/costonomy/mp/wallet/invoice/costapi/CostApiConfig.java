package com.costonomy.mp.wallet.invoice.costapi;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Clock;
import java.time.Duration;

/**
 * The cost-app sign-in, only when the reader is the real one (D-114). The password is read from settings once
 * and held by the session only; the static token override is read on every call so it can be swapped.
 */
@Configuration
@ConditionalOnProperty(name = "costonomy.mp.invoices.reader.provider", havingValue = "HTTP")
public class CostApiConfig {

    public static final String TOKEN_PROPERTY = "costonomy.mp.invoices.reader.token";

    @Bean
    public CostApiSession costApiSession(
            Environment env,
            @Value("${costonomy.mp.invoices.reader.base-url:}") String baseUrl,
            @Value("${costonomy.mp.invoices.reader.username:}") String username,
            @Value("${costonomy.mp.invoices.reader.password:}") String password,
            @Value("${costonomy.mp.invoices.reader.auth-timeout:PT15S}") Duration authTimeout) {
        return new CostApiSession(baseUrl, username, password, () -> env.getProperty(TOKEN_PROPERTY),
                authTimeout, Clock.systemUTC());
    }
}
