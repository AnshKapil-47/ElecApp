package com.elecsarthi.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestDatabaseGuardTest {

    @Test
    @DisplayName("The es_test database is accepted, with or without query parameters")
    void acceptsEsTest() {
        assertDoesNotThrow(() -> TestDatabaseGuard.requireTestDatabase("jdbc:postgresql://127.0.0.1:5321/es_test"));
        assertDoesNotThrow(() -> TestDatabaseGuard.requireTestDatabase("jdbc:postgresql://127.0.0.1:5498/es_test?sslmode=disable"));
    }

    @Test
    @DisplayName("Any other database, a lookalike name or a missing URL is refused, and credentials are never echoed")
    void refusesEverythingElse() {
        for (String url : new String[] {
                "jdbc:postgresql://127.0.0.1:5321/es_dev",
                "jdbc:postgresql://127.0.0.1:5321/es_test_copy",
                "jdbc:postgresql://127.0.0.1:5321/my_es_test",
                "jdbc:postgresql://127.0.0.1:5321/es_dev?currentSchema=es_test",
                "jdbc:postgresql://127.0.0.1:5321/postgres", "", null}) {
            assertThrows(IllegalStateException.class, () -> TestDatabaseGuard.requireTestDatabase(url), String.valueOf(url));
        }
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                TestDatabaseGuard.requireTestDatabase("jdbc:postgresql://127.0.0.1:5321/es_dev?user=u&password=SECRET"));
        assertFalse(ex.getMessage().contains("SECRET"));
        assertTrue(ex.getMessage().contains("es_dev"));
    }
}
