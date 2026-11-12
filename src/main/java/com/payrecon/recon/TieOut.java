package com.payrecon.recon;

import com.payrecon.ledger.LedgerService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The accountant's proof that reconciliation is complete, per bank:
 *   (what we captured) - (cash the bank actually paid, as booked in the ledger)
 *     must equal
 *   the open items the matching engine reported (in transit + missing + in review + short-paid amounts).
 * If the two numbers are equal, every rupee is explained. "Tie out" is the accounting word for this.
 */
public final class TieOut {

    public record Row(String bank, long captured, long settled, long openItems) {
        public long stillOwed() {
            return captured - settled;
        }

        public boolean ties() {
            return stillOwed() == openItems;
        }
    }

    private TieOut() {
    }

    public static List<Row> check(Connection c, int runId, YearMonth period, LocalDate through) throws SQLException {
        LocalDate start = period.atDay(1);
        LocalDate end = LedgerExtractor.endExclusive(period, through);
        Map<String, Long> captured = sums(c,
                "SELECT acquirer, SUM(amount_minor) FROM payment WHERE captured_at >= ? AND captured_at < ? GROUP BY acquirer",
                start.atStartOfDay(), end.atStartOfDay());
        // cash booked to each bank's clearing account (the audit check proves the ledger postings equal this record)
        Map<String, Long> settled = sums(c,
                "SELECT bank, SUM(amount_minor) FROM cash_application"
                        + " WHERE bucket = 'CLEARING' AND value_date <= ? GROUP BY bank",
                through);
        Map<String, Long> open = sums(c,
                "SELECT bank, SUM(CASE WHEN exception_type IN ('IN_TRANSIT', 'MISSING_IN_BANK', 'PENDING_REVIEW')"
                        + " THEN ledger_amount_minor WHEN exception_type = 'AMOUNT_MISMATCH' THEN diff_minor ELSE 0 END)"
                        + " FROM recon_exception WHERE run_id = ? GROUP BY bank",
                runId);
        List<Row> rows = new ArrayList<>();
        for (String bank : LedgerService.ACQUIRERS) {
            rows.add(new Row(bank, captured.getOrDefault(bank, 0L), settled.getOrDefault(bank, 0L),
                    open.getOrDefault(bank, 0L)));
        }
        return rows;
    }

    private static Map<String, Long> sums(Connection c, String sql, Object... params) throws SQLException {
        Map<String, Long> out = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (rs.getString(1) != null) {
                        out.put(rs.getString(1), rs.getLong(2));
                    }
                }
            }
        }
        return out;
    }
}
