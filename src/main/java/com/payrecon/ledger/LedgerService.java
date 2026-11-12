package com.payrecon.ledger;

import com.payrecon.common.ApiException;
import com.payrecon.common.Money;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The double-entry ledger. Every money movement becomes ONE journal entry made of postings
 * whose debits equal its credits. Methods take a {@link Connection} so the caller decides the
 * transaction boundary (the REST API wraps each request in one transaction).
 */
public final class LedgerService {

    public static final List<String> ACQUIRERS = List.of("HDFC", "ICICI", "CARDNET");
    public static final List<String> PAYOUT_BANKS = List.of("HDFC", "ICICI");
    public static final List<String> METHODS = List.of("UPI", "CARD", "NETBANKING");

    /** Upper limit for a single payment in this demo: ₹1 crore. */
    private static final long MAX_PAYMENT_MINOR = 1_000_000_000L;

    public record AccountRef(long id, AccountType type) {
    }

    public record PaymentRecord(String paymentId, String merchantId, long amountMinor, long feeMinor,
                                long refundedMinor, String method, String acquirer, LocalDateTime capturedAt,
                                String payoutId) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("payment_id", paymentId);
            m.put("merchant_id", merchantId);
            m.put("amount_minor", amountMinor);
            m.put("amount_display", Money.inr(amountMinor));
            m.put("fee_minor", feeMinor);
            m.put("refunded_minor", refundedMinor);
            m.put("method", method);
            m.put("acquirer", acquirer);
            m.put("captured_at", capturedAt.toString());
            m.put("payout_id", payoutId);
            return m;
        }
    }

    public record RefundRecord(String refundId, String paymentId, long amountMinor, long refundedTotalMinor,
                               long remainingMinor, LocalDateTime createdAt) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("refund_id", refundId);
            m.put("payment_id", paymentId);
            m.put("amount_minor", amountMinor);
            m.put("refunded_total_minor", refundedTotalMinor);
            m.put("refundable_remaining_minor", remainingMinor);
            m.put("created_at", createdAt.toString());
            return m;
        }
    }

    private final Map<String, AccountRef> accountCache = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ accounts and merchants

    public void ensureSystemAccounts(Connection c) throws SQLException {
        ensureAccount(c, Accounts.SETTLEMENT_CASH, AccountType.ASSET, null);
        for (String bank : ACQUIRERS) {
            ensureAccount(c, Accounts.clearing(bank), AccountType.ASSET, null);
        }
        ensureAccount(c, Accounts.FEE_REVENUE, AccountType.REVENUE, null);
        ensureAccount(c, Accounts.REFUNDS_PAYABLE, AccountType.LIABILITY, null);
        ensureAccount(c, Accounts.SUSPENSE, AccountType.LIABILITY, null);
    }

    public void ensureAccount(Connection c, String code, AccountType type, String merchantId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT IGNORE INTO account (code, type, merchant_id) VALUES (?, ?, ?)")) {
            ps.setString(1, code);
            ps.setString(2, type.name());
            ps.setString(3, merchantId);
            ps.executeUpdate();
        }
    }

    public void ensureMerchant(Connection c, String merchantId, String name, int feeBps, String payoutCycle,
                               String payoutBank) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT IGNORE INTO merchant (merchant_id, name, fee_bps, payout_cycle, payout_bank) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, merchantId);
            ps.setString(2, name);
            ps.setInt(3, feeBps);
            ps.setString(4, payoutCycle);
            ps.setString(5, payoutBank);
            ps.executeUpdate();
        }
        ensureAccount(c, Accounts.merchantPayable(merchantId), AccountType.LIABILITY, merchantId);
    }

    public AccountRef account(Connection c, String code) throws SQLException {
        AccountRef cached = accountCache.get(code);
        if (cached != null) {
            return cached;
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT account_id, type FROM account WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(404, "account_not_found", "No ledger account " + code);
                }
                AccountRef ref = new AccountRef(rs.getLong(1), AccountType.valueOf(rs.getString(2)));
                accountCache.put(code, ref);
                return ref;
            }
        }
    }

    public void clearCache() {
        accountCache.clear();
    }

    // ------------------------------------------------------------------ the core: one balanced entry

    /**
     * Writes one journal entry. Order matters, to avoid deadlocks:
     * 1) insert the entry header, 2) update account balances in account_id order,
     * 3) insert the postings. Every transaction locks accounts in the same order, so two
     * transactions can never wait for each other in a circle. Updating balances BEFORE inserting
     * postings matters too: a posting's foreign-key check takes a shared lock on the account row,
     * and upgrading that shared lock to an exclusive one later is a classic deadlock.
     */
    public long postEntry(Connection c, String entryType, String externalRef, List<Leg> legs) throws SQLException {
        validateBalanced(legs);

        long entryId;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO journal_entry (entry_type, external_ref) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, entryType);
            ps.setString(2, externalRef);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                entryId = rs.getLong(1);
            }
        }

        List<AccountRef> refs = new ArrayList<>(legs.size());
        Map<Long, Long> deltaByAccount = new TreeMap<>(); // TreeMap = sorted by account_id
        for (Leg leg : legs) {
            AccountRef ref = account(c, leg.accountCode());
            refs.add(ref);
            deltaByAccount.merge(ref.id(), ref.type().signedDelta(leg.direction(), leg.amountMinor()), Long::sum);
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE account SET balance_minor = balance_minor + ? WHERE account_id = ?")) {
            for (Map.Entry<Long, Long> e : deltaByAccount.entrySet()) {
                ps.setLong(1, e.getValue());
                ps.setLong(2, e.getKey());
                ps.executeUpdate(); // one by one, so the lock order is exactly the sorted order
            }
        }

        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO posting (entry_id, account_id, direction, amount_minor) VALUES (?, ?, ?, ?)")) {
            for (int i = 0; i < legs.size(); i++) {
                Leg leg = legs.get(i);
                ps.setLong(1, entryId);
                ps.setLong(2, refs.get(i).id());
                ps.setString(3, leg.direction().name());
                ps.setLong(4, leg.amountMinor());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        return entryId;
    }

    /** The golden rule of double-entry bookkeeping: total debits must equal total credits. */
    public static void validateBalanced(List<Leg> legs) {
        if (legs == null || legs.size() < 2) {
            throw new IllegalArgumentException("A journal entry needs at least two legs");
        }
        long debits = 0;
        long credits = 0;
        for (Leg leg : legs) {
            if (leg.direction() == Direction.DEBIT) {
                debits = Math.addExact(debits, leg.amountMinor());
            } else {
                credits = Math.addExact(credits, leg.amountMinor());
            }
        }
        if (debits != credits) {
            throw new IllegalArgumentException("Unbalanced journal entry: debits " + debits + " != credits " + credits);
        }
    }

    // ------------------------------------------------------------------ standard entries (shared by API and generator)

    /** Customer pays: the bank now owes us the gross amount; we owe the merchant gross minus our fee. */
    public static List<Leg> paymentLegs(String merchantId, String acquirer, long amount, long fee) {
        List<Leg> legs = new ArrayList<>(3);
        legs.add(Leg.debit(Accounts.clearing(acquirer), amount));
        legs.add(Leg.credit(Accounts.merchantPayable(merchantId), amount - fee));
        if (fee > 0) {
            legs.add(Leg.credit(Accounts.FEE_REVENUE, fee));
        }
        return legs;
    }

    /** Refund: we owe the merchant less, and owe the customer the refund. */
    public static List<Leg> refundLegs(String merchantId, long amount) {
        return List.of(Leg.debit(Accounts.merchantPayable(merchantId), amount),
                Leg.credit(Accounts.REFUNDS_PAYABLE, amount));
    }

    /** Payout: we owe the merchant less, and cash leaves our bank account. */
    public static List<Leg> payoutLegs(String merchantId, long amount) {
        return List.of(Leg.debit(Accounts.merchantPayable(merchantId), amount),
                Leg.credit(Accounts.SETTLEMENT_CASH, amount));
    }

    // ------------------------------------------------------------------ business operations

    public PaymentRecord capturePayment(Connection c, String paymentId, String merchantId, long amount,
                                        String method, String acquirer, LocalDateTime capturedAt) throws SQLException {
        if (amount <= 0) {
            throw new ApiException(400, "invalid_amount", "amount_minor must be a positive number of paise");
        }
        if (amount > MAX_PAYMENT_MINOR) {
            throw new ApiException(400, "invalid_amount", "amount_minor is above this demo's limit of ₹1,00,00,000");
        }
        if (!METHODS.contains(method)) {
            throw new ApiException(400, "invalid_method", "method must be one of " + METHODS);
        }
        if (!ACQUIRERS.contains(acquirer)) {
            throw new ApiException(400, "invalid_acquirer", "acquirer must be one of " + ACQUIRERS);
        }
        int feeBps = merchantFeeBps(c, merchantId);
        long fee = Money.basisPoints(amount, feeBps);
        long entryId = postEntry(c, "PAYMENT_CAPTURED", paymentId, paymentLegs(merchantId, acquirer, amount, fee));
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO payment (payment_id, merchant_id, amount_minor, fee_minor, method, acquirer, captured_at, entry_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, paymentId);
            ps.setString(2, merchantId);
            ps.setLong(3, amount);
            ps.setLong(4, fee);
            ps.setString(5, method);
            ps.setString(6, acquirer);
            ps.setObject(7, capturedAt);
            ps.setLong(8, entryId);
            ps.executeUpdate();
        }
        return new PaymentRecord(paymentId, merchantId, amount, fee, 0, method, acquirer, capturedAt, null);
    }

    public RefundRecord refund(Connection c, String refundId, String paymentId, long amount, LocalDateTime at)
            throws SQLException {
        if (amount <= 0) {
            throw new ApiException(400, "invalid_amount", "amount_minor must be a positive number of paise");
        }
        String merchantId;
        long paid;
        long refunded;
        // Lock this payment's row. A second refund for the same payment waits here until we commit,
        // so two refunds can never both read the old "refunded" value and over-refund together.
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT merchant_id, amount_minor, refunded_minor FROM payment WHERE payment_id = ? FOR UPDATE")) {
            ps.setString(1, paymentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(404, "payment_not_found", "No payment " + paymentId);
                }
                merchantId = rs.getString(1);
                paid = rs.getLong(2);
                refunded = rs.getLong(3);
            }
        }
        long remaining = paid - refunded;
        if (amount > remaining) {
            throw new ApiException(409, "refund_exceeds_payment",
                    "Only " + Money.inr(remaining) + " of this payment can still be refunded");
        }
        long entryId = postEntry(c, "REFUND", refundId, refundLegs(merchantId, amount));
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE payment SET refunded_minor = refunded_minor + ? WHERE payment_id = ?")) {
            ps.setLong(1, amount);
            ps.setString(2, paymentId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO refund (refund_id, payment_id, amount_minor, created_at, entry_id) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, refundId);
            ps.setString(2, paymentId);
            ps.setLong(3, amount);
            ps.setObject(4, at);
            ps.setLong(5, entryId);
            ps.executeUpdate();
        }
        return new RefundRecord(refundId, paymentId, amount, refunded + amount, remaining - amount, at);
    }

    public PaymentRecord findPayment(Connection c, String paymentId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT payment_id, merchant_id, amount_minor, fee_minor, refunded_minor, method, acquirer, captured_at, payout_id"
                        + " FROM payment WHERE payment_id = ?")) {
            ps.setString(1, paymentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new PaymentRecord(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getString(6), rs.getString(7), rs.getObject(8, LocalDateTime.class),
                        rs.getString(9));
            }
        }
    }

    public int merchantFeeBps(Connection c, String merchantId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT fee_bps FROM merchant WHERE merchant_id = ?")) {
            ps.setString(1, merchantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(404, "merchant_not_found", "No merchant " + merchantId);
                }
                return rs.getInt(1);
            }
        }
    }

    public long accountBalance(Connection c, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT balance_minor FROM account WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(404, "account_not_found", "No ledger account " + code);
                }
                return rs.getLong(1);
            }
        }
    }
}
