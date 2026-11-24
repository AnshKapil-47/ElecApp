package com.elecsarthi.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Encoding and atomicity of {@link VoterCopyWriter}, checked against a scratch table instead of a voter partition. */
@SpringBootTest
@ActiveProfiles("test")
class VoterCopyWriterIntegrationTest {

    private static final String COPY_PROBE = "COPY copy_writer_probe (id, a, b, c) FROM STDIN WITH (FORMAT text, ENCODING 'UTF8')";

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void createProbeTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS copy_writer_probe");
        jdbcTemplate.execute("CREATE TABLE copy_writer_probe (id int PRIMARY KEY, a text, b int, c bytea)");
    }

    @AfterEach
    void dropProbeTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS copy_writer_probe");
    }

    private long copy(List<List<Object>> rows) {
        return transactionTemplate.execute(status -> jdbcTemplate.execute((ConnectionCallback<Long>) connection -> {
            VoterCopyWriter writer = new VoterCopyWriter(connection.unwrap(PGConnection.class).getCopyAPI().copyIn(COPY_PROBE));
            try {
                rows.forEach(writer::writeRow);
                return writer.finish();
            } catch (RuntimeException e) {
                writer.cancel();
                throw e;
            }
        }));
    }

    private static List<Object> row(int id, String text, Integer number, byte[] bytes) {
        return Arrays.asList(id, text, number, bytes);
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM copy_writer_probe", Integer.class);
    }

    @Test
    @DisplayName("Every text-format special character, NULL and bytea round-trips exactly")
    void valuesRoundTripExactly() {
        String[] texts = {
                "tab\tinside", "new\nline", "carriage\rreturn", "windows\r\nline", "back\\slash", "\\N", "\\.", "ends with \\",
                "\\\\double", "\\t is not a tab", "quote \" and ' mix", "a,b;c|d", "",
                "नमस्ते 😀 عربي ‮", "x".repeat(100_000)
        };
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            rows.add(row(i + 1, texts[i], i, null));
        }
        byte[] bytes = {0, 1, (byte) 0xFF, '\\', '\n', '\t'};
        rows.add(row(100, null, null, bytes));
        rows.add(row(101, "NUL\u0000dropped", 7, new byte[0]));

        assertEquals(rows.size(), copy(rows));

        for (int i = 0; i < texts.length; i++) {
            assertEquals(texts[i], jdbcTemplate.queryForObject("SELECT a FROM copy_writer_probe WHERE id = ?", String.class, i + 1),
                    "text " + i + " must be stored exactly");
        }
        assertNull(jdbcTemplate.queryForObject("SELECT a FROM copy_writer_probe WHERE id = 100", String.class), "NULL is not the text \\N");
        assertNull(jdbcTemplate.queryForObject("SELECT b FROM copy_writer_probe WHERE id = 100", Integer.class));
        assertEquals("00 01 ff 5c 0a 09".replace(" ", ""), jdbcTemplate.queryForObject(
                "SELECT encode(c, 'hex') FROM copy_writer_probe WHERE id = 100", String.class));
        assertEquals("NULdropped", jdbcTemplate.queryForObject("SELECT a FROM copy_writer_probe WHERE id = 101", String.class));
        assertEquals("", jdbcTemplate.queryForObject("SELECT encode(c, 'hex') FROM copy_writer_probe WHERE id = 101", String.class));
        assertEquals("\\N", jdbcTemplate.queryForObject("SELECT a FROM copy_writer_probe WHERE id = 6", String.class), "the text \\N stays text");
    }

    @Test
    @DisplayName("A row rejected by PostgreSQL after earlier rows were already sent leaves nothing behind")
    void failureMidLoadRollsBackEveryRow() {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 5_000; i++) {
            rows.add(row(i, "row " + "y".repeat(40), i, null));
        }
        rows.add(row(10, "duplicate primary key", 0, null));

        Throwable failure = assertThrows(RuntimeException.class, () -> copy(rows));

        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertTrue(root.getMessage().contains("duplicate key"), "unexpected failure: " + root.getMessage());
        assertEquals(0, rowCount(), "rows streamed before the failure must not stay");
    }

    @Test
    @DisplayName("Cancelling an open COPY discards the rows written so far")
    void cancelledCopyKeepsNothing() {
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            VoterCopyWriter writer = new VoterCopyWriter(connection.unwrap(PGConnection.class).getCopyAPI().copyIn(COPY_PROBE));
            writer.writeRow(row(1, "kept?", 1, null));
            writer.cancel();
            writer.cancel(); // safe to call again
            return null;
        }));

        assertEquals(0, rowCount());
    }

    @Test
    @DisplayName("Only plain lower-case identifiers and a positive dataset id can reach the COPY statement")
    void identifiersAreValidated() {
        assertEquals("COPY voter_d42 (dataset_id, state) FROM STDIN WITH (FORMAT text, ENCODING 'UTF8')",
                VoterCopyWriter.copyStatement(42, List.of("dataset_id", "state")));
        assertEquals("COPY voter_d1 (a1_b2) FROM STDIN WITH (FORMAT text, ENCODING 'UTF8')",
                VoterCopyWriter.copyStatement(1, List.of("a1_b2")));

        assertThrows(IllegalArgumentException.class, () -> VoterCopyWriter.copyStatement(0, List.of("state")));
        assertThrows(IllegalArgumentException.class, () -> VoterCopyWriter.copyStatement(-5, List.of("state")));
        for (String bad : new String[]{"state; DROP TABLE voter", "State", "1state", "st ate", "st-ate", "state)", "", "\"state\"", "voter.state"}) {
            assertThrows(IllegalArgumentException.class, () -> VoterCopyWriter.copyStatement(7, List.of("dataset_id", bad)),
                    "identifier must be rejected: " + bad);
        }
        assertFalse(VoterSheetHandler.COPY_COLUMNS.isEmpty());
        VoterSheetHandler.COPY_COLUMNS.forEach(column -> assertEquals(column, VoterCopyWriter.requireIdentifier(column)));
    }
}
