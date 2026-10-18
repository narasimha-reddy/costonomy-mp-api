package com.costonomy.mp.common.config;

import jakarta.annotation.PreDestroy;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.Collections;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Releases what the MySQL driver keeps running when this app stops. Costonomy's apps share one Tomcat and a
 * zero-downtime deploy undeploys the previous version while Tomcat keeps running; without this, the driver's
 * abandoned-connection cleanup thread and its DriverManager registration keep the old version's classes in memory
 * (Tomcat logs "appears to have started a thread … failed to stop it") until Tomcat restarts.
 */
@Component
@Slf4j
public class JdbcDriverCleanup {

    @PreDestroy
    public void release() {
        ClassLoader mine = getClass().getClassLoader();
        for (Driver driver : Collections.list(DriverManager.getDrivers())) {
            if (driver.getClass().getClassLoader() == mine) {
                try {
                    DriverManager.deregisterDriver(driver);
                } catch (Exception e) {
                    log.warn("Couldn't unregister JDBC driver {}: {}", driver.getClass().getName(), e.getMessage());
                }
            }
        }
        // The MySQL driver is a runtime dependency, so reach its cleanup thread by name.
        try {
            Class.forName("com.mysql.cj.jdbc.AbandonedConnectionCleanupThread", false, mine)
                    .getMethod("checkedShutdown").invoke(null);
        } catch (ClassNotFoundException e) {
            // no MySQL driver on the classpath
        } catch (Exception e) {
            log.warn("Couldn't stop the MySQL cleanup thread: {}", e.getMessage());
        }
    }
}
