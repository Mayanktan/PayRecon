package com.payrecon.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Creates (or drops and recreates) the MySQL tables from src/main/resources/sql/mysql_schema.sql. */
public final class MySqlSchema {

    /** Child tables first, so foreign keys never block the drop. */
    private static final String[] DROP_ORDER = {
        "exception_event", "recon_exception", "recon_step_timing", "recon_pass_stat", "recon_run",
        "cash_application", "idempotency_key", "payout", "payout_batch", "refund", "payment", "posting", "journal_entry",
        "account", "merchant"
    };

    private MySqlSchema() {
    }

    public static List<String> statements() throws IOException {
        try (InputStream in = MySqlSchema.class.getResourceAsStream("/sql/mysql_schema.sql")) {
            if (in == null) {
                throw new IOException("sql/mysql_schema.sql not found on the classpath");
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            StringBuilder withoutComments = new StringBuilder();
            for (String line : text.split("\n")) {
                if (!line.trim().startsWith("--")) {
                    withoutComments.append(line).append('\n');
                }
            }
            List<String> result = new ArrayList<>();
            for (String stmt : withoutComments.toString().split(";")) {
                if (!stmt.isBlank()) {
                    result.add(stmt.trim());
                }
            }
            return result;
        }
    }

    public static int create(Connection c) throws SQLException, IOException {
        int count = 0;
        try (Statement st = c.createStatement()) {
            for (String sql : statements()) {
                st.execute(sql);
                count++;
            }
        }
        return count;
    }

    public static void dropAll(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            for (String table : DROP_ORDER) {
                st.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }
}
