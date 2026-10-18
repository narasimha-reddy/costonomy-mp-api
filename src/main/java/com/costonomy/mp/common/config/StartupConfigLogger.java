package com.costonomy.mp.common.config;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/**
 * Logs every property and environment variable once at startup, grouped by where it comes from (highest precedence
 * first) with the value the app actually uses, so a server's configuration can be checked from its log. Anything
 * whose name contains password, pwd, key, secret or token (any case) is masked.
 *
 * <p>Runs as soon as the configuration is assembled, before anything connects to a database, so the list is there
 * even when startup then fails. Registered in META-INF/spring.factories; runs after Spring Boot's logging setup.
 * Off with {@code costonomy.startup.log-config=false}.
 */
public class StartupConfigLogger implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(StartupConfigLogger.class);
    private static final Pattern SENSITIVE = Pattern.compile("password|pwd|key|secret|token", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESOURCE = Pattern.compile("\\[(?:class path resource \\[)?([^\\]]+)\\]");

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment env = event.getEnvironment();
        if (!env.getProperty("costonomy.startup.log-config", Boolean.class, true)) {
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        Map<String, Map<String, String>> bySource = new LinkedHashMap<>();
        for (PropertySource<?> source : env.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            Map<String, String> lines = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (String name : enumerable.getPropertyNames()) {
                if (seen.add(name)) { // a higher-precedence source already decided this one
                    lines.put(name, display(env, enumerable, name));
                }
            }
            if (!lines.isEmpty()) {
                bySource.put(label(source.getName()), lines);
            }
        }
        StringBuilder out = new StringBuilder("Configuration (")
                .append(seen.size()).append(" properties, profiles ")
                .append(String.join(",", env.getActiveProfiles())).append("):");
        bySource.forEach((sourceName, lines) -> {
            out.append("\n  [").append(sourceName).append("]");
            lines.forEach((name, value) -> out.append("\n    ").append(name).append(" = ").append(value));
        });
        log.info(out.toString());
    }

    private static String display(ConfigurableEnvironment env, PropertySource<?> source, String name) {
        String value;
        try {
            value = env.getProperty(name);
        } catch (IllegalArgumentException unresolvable) {
            return source.getProperty(name) + "   (unresolved: " + unresolvable.getMessage() + ")";
        }
        if (value == null) {
            value = String.valueOf(source.getProperty(name));
        }
        if (SENSITIVE.matcher(name).find()) {
            return value.isEmpty() ? "(empty)" : "******";
        }
        return value;
    }

    /** "Config resource 'class path resource [application.properties]' via …" → "application.properties". */
    private static String label(String sourceName) {
        Matcher m = RESOURCE.matcher(sourceName);
        return switch (sourceName) {
            case "systemEnvironment" -> "environment variables";
            case "systemProperties" -> "JVM system properties";
            default -> m.find() ? m.group(1) : sourceName;
        };
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE; // after LoggingApplicationListener has set logging up
    }
}
