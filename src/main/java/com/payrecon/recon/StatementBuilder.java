package com.payrecon.recon;

import com.payrecon.db.Db;
import com.payrecon.mongo.MongoStore;
import org.bson.Document;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds one MongoDB document per merchant payout: which payments, fees and refunds it contains,
 * plus the bank status of its batch from reconciliation (pass 3). Rebuilt from MySQL every run.
 */
public final class StatementBuilder {

    private StatementBuilder() {
    }

    public static int build(Db db, MongoStore mongo, YearMonth period, LocalDate through,
                            Map<String, String> batchStatus) throws SQLException {
        LocalDate start = period.atDay(1);
        LocalDate end = LedgerExtractor.endExclusive(period, through);
        Map<String, Document> docs = new LinkedHashMap<>();
        Map<String, List<Document>> payments = new HashMap<>();
        Map<String, List<Document>> refunds = new HashMap<>();
        Map<String, long[]> sums = new HashMap<>(); // payments, gross, fees, refunds

        try (Connection c = db.connect()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT p.payout_id, p.merchant_id, m.name, p.batch_id, b.bank, p.amount_minor, p.payout_date"
                            + " FROM payout p JOIN payout_batch b ON b.batch_id = p.batch_id"
                            + " JOIN merchant m ON m.merchant_id = p.merchant_id"
                            + " WHERE p.payout_date >= ? AND p.payout_date < ?")) {
                ps.setObject(1, start);
                ps.setObject(2, end);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String payoutId = rs.getString(1);
                        String batchId = rs.getString(4);
                        docs.put(payoutId, new Document("_id", payoutId)
                                .append("merchant_id", rs.getString(2))
                                .append("merchant_name", rs.getString(3))
                                .append("payout_date", rs.getObject(7, LocalDate.class).toString())
                                .append("batch_id", batchId)
                                .append("payout_bank", rs.getString(5))
                                .append("amount_minor", rs.getLong(6))
                                .append("currency", "INR")
                                .append("bank_status", batchStatus.getOrDefault(batchId, "NOT_RECONCILED")));
                        payments.put(payoutId, new ArrayList<>());
                        refunds.put(payoutId, new ArrayList<>());
                        sums.put(payoutId, new long[4]);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT payment_id, payout_id, captured_at, method, amount_minor, fee_minor FROM payment"
                            + " WHERE payout_id IS NOT NULL AND captured_at >= ? AND captured_at < ?",
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                ps.setFetchSize(Integer.MIN_VALUE);
                ps.setObject(1, start.atStartOfDay());
                ps.setObject(2, end.atStartOfDay());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String payoutId = rs.getString(2);
                        List<Document> list = payments.get(payoutId);
                        if (list == null) {
                            continue;
                        }
                        long gross = rs.getLong(5);
                        long fee = rs.getLong(6);
                        list.add(new Document("payment_id", rs.getString(1))
                                .append("captured_at", rs.getObject(3, LocalDateTime.class).toString())
                                .append("method", rs.getString(4))
                                .append("gross_minor", gross)
                                .append("fee_minor", fee)
                                .append("net_minor", gross - fee));
                        long[] s = sums.get(payoutId);
                        s[0]++;
                        s[1] += gross;
                        s[2] += fee;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT refund_id, payment_id, payout_id, amount_minor, created_at FROM refund"
                            + " WHERE payout_id IS NOT NULL AND created_at >= ? AND created_at < ?")) {
                ps.setObject(1, start.atStartOfDay());
                ps.setObject(2, end.atStartOfDay());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String payoutId = rs.getString(3);
                        List<Document> list = refunds.get(payoutId);
                        if (list == null) {
                            continue;
                        }
                        list.add(new Document("refund_id", rs.getString(1))
                                .append("payment_id", rs.getString(2))
                                .append("amount_minor", rs.getLong(4))
                                .append("created_at", rs.getObject(5, LocalDateTime.class).toString()));
                        sums.get(payoutId)[3] += rs.getLong(4);
                    }
                }
            }
        }

        for (Map.Entry<String, Document> e : docs.entrySet()) {
            long[] s = sums.get(e.getKey());
            long net = s[1] - s[2] - s[3];
            long amount = e.getValue().getLong("amount_minor");
            e.getValue()
                    .append("summary", new Document("payments", s[0])
                            .append("gross_minor", s[1])
                            .append("fees_minor", s[2])
                            .append("refunds_minor", s[3])
                            .append("net_minor", net)
                            .append("carried_from_earlier_minor", amount - net))
                    .append("payments", payments.get(e.getKey()))
                    .append("refunds", refunds.get(e.getKey()));
        }
        mongo.upsertStatements(docs.values());
        return docs.size();
    }
}
