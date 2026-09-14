package com.costonomy.mp.common.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
        return builder -> builder
                // ISO-8601 strings, not epoch numbers. Deadlines and timestamps
                // are parsed by the mobile client with Date.parse(); a numeric
                // epoch would silently lose the timezone contract.
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // An unknown field is a client sending something we do not
                // understand. Failing loudly in development is how a typo'd
                // field name gets caught before it ships as a silent no-op.
                .featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
