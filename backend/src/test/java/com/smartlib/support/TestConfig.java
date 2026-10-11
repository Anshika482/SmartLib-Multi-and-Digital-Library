package com.smartlib.support;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestConfig {

    @Bean
    @Primary
    CapturingMailTransport capturingMailTransport() {
        return new CapturingMailTransport();
    }

    /** Fresh schema for every test JVM: clean, then migrate. */
    @Bean
    FlywayMigrationStrategy cleanMigrate() {
        return flyway -> {
            flyway.clean();
            flyway.migrate();
        };
    }
}
