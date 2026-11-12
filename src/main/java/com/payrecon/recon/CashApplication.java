package com.payrecon.recon;

import com.payrecon.db.Db;
import com.payrecon.ledger.Accounts;
import com.payrecon.ledger.Leg;
import com.payrecon.ledger.LedgerService;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * "Cash application" = booking the money banks actually paid us into the ledger, once we know what it was for.
 *   CLEARING  (matched to a payment):  debit SETTLEMENT_CASH, credit CLEARING:<bank>  - the bank no longer owes it
 *   SUSPENSE  (not identified yet):     debit SETTLEMENT_CASH, credit SUSPENSE          - parked until someone knows
 *
 * The table cash_application remembers, PER BANK LINE, where its money was booked. Each run books only the
 * CHANGES compared with that record:
 *   new line                         -> book it (aggregated into one entry per bank per day)
 *   was SUSPENSE, now matched        -> move it: debit SUSPENSE, credit CLEARING ("clearing the suspense account")
 *   was CLEARING, now unidentified   -> move it back: debit CLEARING, credit SUSPENSE
 *   unchanged                        -> nothing
 * Ledger entries and the per-line record are written in ONE transaction, so they can never disagree.
 * This makes re-running a reconciliation safe (idempotent), even when later runs match more lines.
 */
public final class CashApplication {

    public enum Bucket { CLEARING, SUSPENSE }

    public record Line(String lineId, String bank, LocalDate valueDate, long amount, Bucket bucket) {
    }

    public record Result(int newLines, long newToClearing, long newToSuspense, int movedToClearing,
                         long movedToClearingMinor, int movedToSuspense, long movedToSuspenseMinor, int unchanged) {
    }

    private CashApplication() {
    }

    public static Result apply(Db db, LedgerService ledger, int runId, List<Line> desired) throws SQLException {
        return db.inTransaction(c -> {
            Map<String, Bucket> existing = new HashMap<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT bank_line_id, bucket FROM cash_application")) {
                while (rs.next()) {
                    existing.put(rs.getString(1), Bucket.valueOf(rs.getString(2)));
                }
            }

            Map<String, Long> newClearing = new TreeMap<>(); // "BANK:date" -> amount
            Map<String, Long> newSuspense = new TreeMap<>();
            Map<String, Long> toClearing = new TreeMap<>();  // "BANK" -> amount
            Map<String, Long> toSuspense = new TreeMap<>();
            int newLines = 0;
            int movedToClearing = 0;
            int movedToSuspense = 0;
            int unchanged = 0;
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO cash_application (bank_line_id, bank, value_date, amount_minor, bucket, run_id)"
                            + " VALUES (?, ?, ?, ?, ?, ?)");
                 PreparedStatement update = c.prepareStatement(
                         "UPDATE cash_application SET bucket = ?, run_id = ? WHERE bank_line_id = ?")) {
                int pending = 0;
                for (Line line : desired) {
                    Bucket old = existing.get(line.lineId());
                    if (old == null) {
                        String key = line.bank() + ":" + line.valueDate();
                        (line.bucket() == Bucket.CLEARING ? newClearing : newSuspense).merge(key, line.amount(), Long::sum);
                        insert.setString(1, line.lineId());
                        insert.setString(2, line.bank());
                        insert.setObject(3, line.valueDate());
                        insert.setLong(4, line.amount());
                        insert.setString(5, line.bucket().name());
                        insert.setInt(6, runId);
                        insert.addBatch();
                        existing.put(line.lineId(), line.bucket());
                        newLines++;
                    } else if (old != line.bucket()) {
                        if (line.bucket() == Bucket.CLEARING) {
                            toClearing.merge(line.bank(), line.amount(), Long::sum);
                            movedToClearing++;
                        } else {
                            toSuspense.merge(line.bank(), line.amount(), Long::sum);
                            movedToSuspense++;
                        }
                        update.setString(1, line.bucket().name());
                        update.setInt(2, runId);
                        update.setString(3, line.lineId());
                        update.addBatch();
                        existing.put(line.lineId(), line.bucket());
                    } else {
                        unchanged++;
                    }
                    if (++pending % 5_000 == 0) {
                        insert.executeBatch();
                        update.executeBatch();
                    }
                }
                insert.executeBatch();
                update.executeBatch();
            }

            String prefix = "run" + runId + ":";
            long clearingTotal = 0;
            for (Map.Entry<String, Long> e : newClearing.entrySet()) {
                String bank = e.getKey().substring(0, e.getKey().indexOf(':'));
                ledger.postEntry(c, "SETTLEMENT_RECEIVED", prefix + e.getKey(), List.of(
                        Leg.debit(Accounts.SETTLEMENT_CASH, e.getValue()), Leg.credit(Accounts.clearing(bank), e.getValue())));
                clearingTotal += e.getValue();
            }
            long suspenseTotal = 0;
            for (Map.Entry<String, Long> e : newSuspense.entrySet()) {
                ledger.postEntry(c, "UNIDENTIFIED_RECEIPT", prefix + e.getKey(), List.of(
                        Leg.debit(Accounts.SETTLEMENT_CASH, e.getValue()), Leg.credit(Accounts.SUSPENSE, e.getValue())));
                suspenseTotal += e.getValue();
            }
            long movedInTotal = 0;
            for (Map.Entry<String, Long> e : toClearing.entrySet()) {
                ledger.postEntry(c, "SUSPENSE_CLEARED", prefix + e.getKey(), List.of(
                        Leg.debit(Accounts.SUSPENSE, e.getValue()), Leg.credit(Accounts.clearing(e.getKey()), e.getValue())));
                movedInTotal += e.getValue();
            }
            long movedOutTotal = 0;
            for (Map.Entry<String, Long> e : toSuspense.entrySet()) {
                ledger.postEntry(c, "MOVED_TO_SUSPENSE", prefix + e.getKey(), List.of(
                        Leg.debit(Accounts.clearing(e.getKey()), e.getValue()), Leg.credit(Accounts.SUSPENSE, e.getValue())));
                movedOutTotal += e.getValue();
            }
            return new Result(newLines, clearingTotal, suspenseTotal, movedToClearing, movedInTotal, movedToSuspense,
                    movedOutTotal, unchanged);
        });
    }
}
