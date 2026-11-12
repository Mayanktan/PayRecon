package com.payrecon.db;

import com.payrecon.config.Config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * MySQL access. {@link #inTransaction} is the important method: everything inside it either fully
 * happens or fully rolls back (atomicity), and it retries automatically when MySQL reports a deadlock.
 */
public final class Db {

    /** MySQL error codes we care about. */
    public static final int ER_DUP_ENTRY = 1062;
    public static final int ER_LOCK_WAIT_TIMEOUT = 1205;
    public static final int ER_LOCK_DEADLOCK = 1213;

    private final String url;
    private final String user;
    private final String password;

    public Db(Config config) {
        this.url = config.mysqlUrl();
        this.user = config.mysqlUser();
        this.password = config.mysqlPassword();
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    /** Runs {@code work} in one transaction (READ COMMITTED). Retries up to 5 times on deadlock / lock timeout. */
    public <T> T inTransaction(Work<T> work) throws SQLException {
        int attempt = 0;
        while (true) {
            attempt++;
            try (Connection c = connect()) {
                c.setAutoCommit(false);
                c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                try {
                    T result = work.run(c);
                    c.commit();
                    return result;
                } catch (SQLException e) {
                    rollbackQuietly(c);
                    if (isRetryable(e) && attempt < 5) {
                        sleep(20L * attempt * attempt);
                        continue;
                    }
                    throw e;
                } catch (RuntimeException e) {
                    rollbackQuietly(c);
                    throw e;
                }
            }
        }
    }

    public static boolean isRetryable(SQLException e) {
        return e.getErrorCode() == ER_LOCK_DEADLOCK || e.getErrorCode() == ER_LOCK_WAIT_TIMEOUT;
    }

    public static boolean isDuplicateKey(SQLException e) {
        return e.getErrorCode() == ER_DUP_ENTRY;
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
            // the connection is being thrown away anyway
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
