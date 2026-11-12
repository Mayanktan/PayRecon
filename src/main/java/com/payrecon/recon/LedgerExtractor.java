package com.payrecon.recon;

import com.payrecon.common.Tsv;
import com.payrecon.db.Db;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Copies the period's payments and payouts out of MySQL into tab-separated files for MapReduce.
 * Uses MySQL "streaming" (fetch size = Integer.MIN_VALUE): rows arrive one at a time instead of
 * the driver loading every row into memory first.
 */
public final class LedgerExtractor {

    public record Counts(int payments, int payouts) {
    }

    private static final int PAYMENT_FILES = 4; // several files -> several map tasks in parallel

    private LedgerExtractor() {
    }

    /** First day after the window we reconcile: the end of the period or the cutoff, whichever is earlier. */
    public static LocalDate endExclusive(YearMonth period, LocalDate through) {
        LocalDate periodEnd = period.atEndOfMonth().plusDays(1);
        LocalDate cutoffEnd = through.plusDays(1);
        return periodEnd.isBefore(cutoffEnd) ? periodEnd : cutoffEnd;
    }

    public static Counts extract(Db db, YearMonth period, LocalDate through, Path paymentsDir, Path payoutsDir)
            throws SQLException, IOException {
        LocalDate start = period.atDay(1);
        LocalDate end = endExclusive(period, through);
        Files.createDirectories(paymentsDir);
        Files.createDirectories(payoutsDir);

        int payments = 0;
        BufferedWriter[] writers = new BufferedWriter[PAYMENT_FILES];
        try (Connection c = db.connect()) {
            for (int i = 0; i < PAYMENT_FILES; i++) {
                writers[i] = Files.newBufferedWriter(paymentsDir.resolve(String.format("part-%05d", i)));
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT payment_id, merchant_id, acquirer, amount_minor, DATE(captured_at) FROM payment"
                            + " WHERE captured_at >= ? AND captured_at < ?",
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                ps.setFetchSize(Integer.MIN_VALUE);
                ps.setObject(1, start.atStartOfDay());
                ps.setObject(2, end.atStartOfDay());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        BufferedWriter w = writers[payments % PAYMENT_FILES];
                        w.write(Tsv.join(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                                rs.getObject(5, LocalDate.class)));
                        w.newLine();
                        payments++;
                    }
                }
            } finally {
                for (BufferedWriter w : writers) {
                    if (w != null) {
                        w.close();
                    }
                }
            }

            int payouts = 0;
            try (BufferedWriter w = Files.newBufferedWriter(payoutsDir.resolve("part-00000"));
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT p.payout_id, p.batch_id, b.bank, p.amount_minor, p.payout_date, p.merchant_id"
                                 + " FROM payout p JOIN payout_batch b ON b.batch_id = p.batch_id"
                                 + " WHERE p.payout_date >= ? AND p.payout_date < ?")) {
                ps.setObject(1, start);
                ps.setObject(2, end);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        w.write(Tsv.join(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                                rs.getObject(5, LocalDate.class), rs.getString(6)));
                        w.newLine();
                        payouts++;
                    }
                }
            }
            return new Counts(payments, payouts);
        }
    }
}
