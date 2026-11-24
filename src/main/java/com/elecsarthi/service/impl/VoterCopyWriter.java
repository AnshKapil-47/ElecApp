package com.elecsarthi.service.impl;

import org.postgresql.copy.CopyIn;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Encodes voter rows in PostgreSQL's COPY text format and streams them into an open {@link CopyIn}. Rows are
 * buffered only up to {@link #FLUSH_THRESHOLD_CHARS} characters, so memory use does not depend on the file size.
 *
 * <p>Text format rules applied here: fields are separated by a tab and rows end with a newline, so backslash, tab,
 * newline and carriage return inside a value are escaped; NULL is written as {@code \N} (so a literal "\N" in the
 * data, whose backslash is escaped, stays text); bytea is written in hex form; NUL cannot be stored in PostgreSQL
 * text and is dropped.
 */
final class VoterCopyWriter {

    private static final int FLUSH_THRESHOLD_CHARS = 32 * 1024;
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");

    private final CopyIn copy;
    private final StringBuilder buffer = new StringBuilder(FLUSH_THRESHOLD_CHARS + 4096);

    VoterCopyWriter(CopyIn copy) {
        this.copy = copy;
    }

    /**
     * Builds the COPY statement for one dataset partition. Every identifier is validated, because identifiers cannot
     * be bind parameters: the partition name is derived from a numeric id and the columns are fixed constants.
     */
    static String copyStatement(long datasetId, List<String> columns) {
        if (datasetId <= 0) {
            throw new IllegalArgumentException("Dataset id must be positive");
        }
        columns.forEach(VoterCopyWriter::requireIdentifier);
        return "COPY voter_d" + datasetId + " (" + String.join(", ", columns) + ") FROM STDIN WITH (FORMAT text, ENCODING 'UTF8')";
    }

    static String requireIdentifier(String identifier) {
        if (!IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Not a safe SQL identifier: " + identifier);
        }
        return identifier;
    }

    void writeRow(List<Object> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                buffer.append('\t');
            }
            appendValue(values.get(i));
        }
        buffer.append('\n');
        if (buffer.length() >= FLUSH_THRESHOLD_CHARS) {
            flush();
        }
    }

    /** Sends the remaining rows and completes the COPY; returns the number of rows PostgreSQL loaded. */
    long finish() {
        flush();
        try {
            return copy.endCopy();
        } catch (SQLException e) {
            throw new IllegalStateException("COPY into the dataset partition failed", e);
        }
    }

    /** Aborts the COPY so nothing from this load is kept. Safe to call when the COPY has already ended or failed. */
    void cancel() {
        try {
            if (copy.isActive()) {
                copy.cancelCopy();
            }
        } catch (SQLException ignored) {
            // The surrounding transaction is rolled back, which discards the load either way.
        }
    }

    private void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        byte[] bytes = buffer.toString().getBytes(StandardCharsets.UTF_8);
        buffer.setLength(0);
        try {
            copy.writeToCopy(bytes, 0, bytes.length);
        } catch (SQLException e) {
            throw new IllegalStateException("COPY into the dataset partition failed", e);
        }
    }

    private void appendValue(Object value) {
        if (value == null) {
            buffer.append("\\N");
        } else if (value instanceof byte[] bytes) {
            buffer.append("\\\\x").append(HexFormat.of().formatHex(bytes));
        } else if (value instanceof String text) {
            appendText(text);
        } else {
            buffer.append(value);
        }
    }

    private void appendText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> buffer.append("\\\\");
                case '\t' -> buffer.append("\\t");
                case '\n' -> buffer.append("\\n");
                case '\r' -> buffer.append("\\r");
                case '\0' -> { }
                default -> buffer.append(c);
            }
        }
    }
}
