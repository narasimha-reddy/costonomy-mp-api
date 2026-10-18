package com.costonomy.mp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.context.support.StandardServletEnvironment;

import java.util.Map;

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

    /**
     * MP's own external config folder on the shared Tomcat. The Tomcat also runs costonomy-api, whose setenv.sh may
     * pass {@code -Dspring.config.additional-location=file:/opt/costonomy/config/} to the whole JVM; that folder holds
     * Core's datasource settings, which would point MP at Core's database. This app's environment puts its own
     * (optional, normally absent) folder first, so the JVM-wide setting never applies here, as in costonomy-common-api.
     */
    static final String TOMCAT_CONFIG_LOCATION = "optional:file:/opt/costonomy/mp-api-config/";

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        StandardServletEnvironment environment = new StandardServletEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("mpApiConfigLocation",
                Map.of("spring.config.additional-location", TOMCAT_CONFIG_LOCATION)));
        return builder.environment(environment).sources(CostonomyMpApplication.class);
    }

    public static void main(String[] args) {
        SpringApplication.run(CostonomyMpApplication.class, args);
    }
}
