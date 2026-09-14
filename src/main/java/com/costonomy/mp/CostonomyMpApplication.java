package com.costonomy.mp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Costonomy MP (Mandi) — restaurant procurement marketplace.
 *
 * <p>Packaged as a WAR and deployed onto the same Tomcat infrastructure as
 * {@code costonomy-api}, so this extends {@link SpringBootServletInitializer}
 * to be bootstrapped by the container rather than by an embedded server. It
 * still runs standalone via {@code mvn spring-boot:run} for local development.
 *
 * <p>The application is a modular monolith. Each top-level package under
 * {@code com.costonomy.mp} is a module that owns its entities, repositories,
 * services and controllers; modules talk to each other through services, never
 * by reaching into another module's repositories. {@code common} is the shared
 * kernel every module may depend on.
 */
@SpringBootApplication
@EnableScheduling
public class CostonomyMpApplication extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(CostonomyMpApplication.class);
    }

    public static void main(String[] args) {
        SpringApplication.run(CostonomyMpApplication.class, args);
    }
}
