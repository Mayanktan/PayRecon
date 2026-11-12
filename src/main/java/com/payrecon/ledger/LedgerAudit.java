package com.payrecon.ledger;

import com.payrecon.common.Money;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Checks the rules a correct double-entry ledger must always satisfy. Run it any time: ./payrecon audit */
public final class LedgerAudit {

    public record Check(String name, boolean passed, String detail) {
    }

    private LedgerAudit() {
    }

    public static List<Check> run(Connection c) throws SQLException {
        List<Check> checks = new ArrayList<>();

        long unbalanced = longs(c, "SELECT COUNT(*) FROM (SELECT entry_id FROM posting GROUP BY entry_id"
                + " HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0) t")[0];
        checks.add(new Check("Every journal entry balances (debits = credits)", unbalanced == 0,
                unbalanced + " unbalanced entries"));

        long thin = longs(c, "SELECT COUNT(*) FROM journal_entry e LEFT JOIN"
                + " (SELECT entry_id, COUNT(*) AS n FROM posting GROUP BY entry_id) p ON p.entry_id = e.entry_id"
                + " WHERE COALESCE(p.n, 0) < 2")[0];
        checks.add(new Check("Every journal entry has at least two postings", thin == 0,
                thin + " entries with fewer than two postings"));

        long[] dc = longs(c, "SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount_minor END), 0),"
                + " COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount_minor END), 0) FROM posting");
        checks.add(new Check("Trial balance: total debits = total credits", dc[0] == dc[1],
                "debits " + Money.inr(dc[0]) + ", credits " + Money.inr(dc[1])));

        long drift = longs(c, "SELECT COUNT(*) FROM account a LEFT JOIN ("
                + " SELECT p.account_id, SUM(CASE WHEN (a2.type IN ('ASSET', 'EXPENSE')) = (p.direction = 'DEBIT')"
                + " THEN p.amount_minor ELSE -p.amount_minor END) AS bal"
                + " FROM posting p JOIN account a2 ON a2.account_id = p.account_id GROUP BY p.account_id) x"
                + " ON x.account_id = a.account_id WHERE a.balance_minor <> COALESCE(x.bal, 0)")[0];
        checks.add(new Check("Every stored account balance equals the sum of its postings", drift == 0,
                drift + " accounts out of sync"));

        long[] eq = longs(c, "SELECT COALESCE(SUM(CASE WHEN type = 'ASSET' THEN balance_minor END), 0),"
                + " COALESCE(SUM(CASE WHEN type = 'LIABILITY' THEN balance_minor END), 0),"
                + " COALESCE(SUM(CASE WHEN type = 'REVENUE' THEN balance_minor END), 0),"
                + " COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN balance_minor END), 0) FROM account");
        checks.add(new Check("Accounting equation: assets = liabilities + revenue - expenses",
                eq[0] == eq[1] + eq[2] - eq[3],
                "assets " + Money.inr(eq[0]) + " = liabilities " + Money.inr(eq[1]) + " + revenue "
                        + Money.inr(eq[2]) + " - expenses " + Money.inr(eq[3])));

        long over = longs(c, "SELECT COUNT(*) FROM payment WHERE refunded_minor > amount_minor")[0];
        checks.add(new Check("No payment is refunded more than was paid", over == 0, over + " over-refunded payments"));

        long refundDrift = longs(c, "SELECT COUNT(*) FROM payment p LEFT JOIN"
                + " (SELECT payment_id, SUM(amount_minor) AS s FROM refund GROUP BY payment_id) r ON r.payment_id = p.payment_id"
                + " WHERE p.refunded_minor <> COALESCE(r.s, 0)")[0];
        checks.add(new Check("Each payment's refunded total equals the sum of its refunds", refundDrift == 0,
                refundDrift + " payments out of sync"));

        // Cash application: what the ledger booked must equal the per-bank-line record in cash_application.
        long[] clearing = longs(c, "SELECT"
                + " COALESCE((SELECT SUM(CASE WHEN p.direction = 'CREDIT' THEN p.amount_minor ELSE -p.amount_minor END)"
                + "   FROM posting p JOIN journal_entry e ON e.entry_id = p.entry_id JOIN account a ON a.account_id = p.account_id"
                + "   WHERE e.entry_type IN ('SETTLEMENT_RECEIVED', 'SUSPENSE_CLEARED', 'MOVED_TO_SUSPENSE')"
                + "   AND a.code LIKE 'CLEARING:%'), 0),"
                + " COALESCE((SELECT SUM(amount_minor) FROM cash_application WHERE bucket = 'CLEARING'), 0)");
        checks.add(new Check("Cash booked to clearing accounts equals the per-line cash application record",
                clearing[0] == clearing[1], "ledger " + Money.inr(clearing[0]) + ", record " + Money.inr(clearing[1])));

        long[] suspense = longs(c, "SELECT"
                + " COALESCE((SELECT balance_minor FROM account WHERE code = 'SUSPENSE'), 0),"
                + " COALESCE((SELECT SUM(amount_minor) FROM cash_application WHERE bucket = 'SUSPENSE'), 0)");
        checks.add(new Check("Suspense account balance equals the unidentified bank money on record",
                suspense[0] == suspense[1], "ledger " + Money.inr(suspense[0]) + ", record " + Money.inr(suspense[1])));

        return checks;
    }

    private static long[] longs(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            int n = rs.getMetaData().getColumnCount();
            long[] out = new long[n];
            for (int i = 0; i < n; i++) {
                out[i] = rs.getLong(i + 1);
            }
            return out;
        }
    }
}
