package com.elecsarthi.support;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Fails fast, before any bean or test method runs, when a Spring test context is pointed at anything other
 * than the {@value #REQUIRED_DATABASE} database. The integration tests TRUNCATE every table, so running them
 * against the dev database ({@code es_dev}) or any other database would destroy its data.
 * <p>
 * Registered for all Spring test contexts in {@code src/test/resources/META-INF/spring.factories}.
 */
public class TestDatabaseGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final String REQUIRED_DATABASE = "es_test";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        requireTestDatabase(context.getEnvironment().getProperty("spring.datasource.url"));
    }

    /** @throws IllegalStateException unless the JDBC URL ends with {@code /es_test} (query parameters ignored) */
    static void requireTestDatabase(String jdbcUrl) {
        // anything after '?' or ';' may carry credentials, so it is neither checked nor printed
        String location = jdbcUrl == null ? "" : jdbcUrl.split("[?;]", 2)[0];
        if (!location.endsWith("/" + REQUIRED_DATABASE)) {
            throw new IllegalStateException("Refusing to run integration tests: spring.datasource.url must end with /"
                    + REQUIRED_DATABASE + " because the tests TRUNCATE all tables, but it is '"
                    + (location.isEmpty() ? "<not set>" : location) + "'.");
        }
    }
}
