package com.payrecon.gen;

import com.payrecon.common.ApiException;
import com.payrecon.common.FileUtil;
import com.payrecon.common.Money;
import com.payrecon.common.TablePrinter;
import com.payrecon.config.Config;
import com.payrecon.db.Db;
import com.payrecon.ledger.AccountType;
import com.payrecon.ledger.Direction;
import com.payrecon.ledger.Leg;
import com.payrecon.ledger.LedgerService;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Creates one realistic month of business, deterministically from a seed (same seed = same data):
 * merchants, customer payments, refunds and merchant payouts, all booked in the double-entry ledger,
 * plus the settlement files three banks would send, with known problems planted in them.
 * Every planted problem is written to data/answer_key.csv so `verify` can prove the engine finds them.
 */
public final class DataGenerator {

    public record Options(YearMonth month, int merchants, int payments, long seed, int extraDays) {
    }

    static final class Merchant {
        String id;
        String name;
        int feeBps;
        String cycle;
        String payoutBank;
    }

    static final class Payment {
        String id;
        Merchant merchant;
        long amount;
        long fee;
        String method;
        String acquirer;
        LocalDateTime at;
        String payoutId;
        long refunded;
    }

    static final class Refund {
        String id;
        Payment payment;
        long amount;
        LocalDateTime at;
        String payoutId;
    }

    static final class Payout {
        String id;
        Merchant merchant;
        String batchId;
        long amount;
        LocalDate date;
    }

    static final class Batch {
        String id;
        String bank;
        LocalDate date;
        long total;
        int count;
    }

    private static final String[] NAME_A = {"Sharma", "Gupta", "Blue Lotus", "Green Leaf", "Sunrise", "Royal", "Metro",
        "Lakshmi", "Krishna", "Urban", "Golden", "Silver Oak", "Coastal", "Himalaya", "Saffron", "Nilgiri", "Ganga"};
    private static final String[] NAME_B = {"Electronics", "Cafe", "Pharmacy", "Textiles", "Books", "Sweets", "Mobiles",
        "Grocers", "Fitness", "Travels", "Bakery", "Footwear", "Opticals", "Hardware", "Restaurant", "Toys"};
    private static final long[] ROUND_RUPEES = {100, 200, 250, 500, 1000, 2000, 5000};
    private static final int[] HOUR_WEIGHTS = {1, 1, 1, 1, 1, 1, 2, 3, 4, 5, 6, 6, 7, 7, 6, 6, 6, 7, 8, 9, 9, 8, 5, 2};
    private static final int CHUNK = 5_000;

    private final Config config;
    private final Db db;
    private final LedgerService ledger;

    public DataGenerator(Config config, Db db, LedgerService ledger) {
        this.config = config;
        this.db = db;
        this.ledger = ledger;
    }

    public void run(Options o, PrintStream out) throws Exception {
        long t0 = System.nanoTime();
        Random rnd = new Random(o.seed());
        requireEmptyLedger();
        Path data = config.dataDir();
        for (String dir : List.of("bank_files", "work", "warehouse")) {
            FileUtil.deleteRecursively(data.resolve(dir));
        }
        Files.createDirectories(data);
        Files.deleteIfExists(data.resolve("answer_key.csv"));

        out.printf("Generating %s: %,d merchants, %,d payments (seed %d)%n", o.month(), o.merchants(), o.payments(), o.seed());
        List<Merchant> merchants = merchants(o, rnd);
        List<Payment> payments = payments(o, rnd, merchants);
        List<Refund> refunds = refunds(o, rnd, payments);
        List<Batch> batches = new ArrayList<>();
        List<Payout> payouts = payouts(o, merchants, payments, refunds, batches);
        long gross = payments.stream().mapToLong(p -> p.amount).sum();
        out.printf("  in memory: %,d payments (%s), %,d refunds, %,d payouts in %,d batches%n",
                payments.size(), Money.inr(gross), refunds.size(), payouts.size(), batches.size());

        long step = System.nanoTime();
        writeLedger(merchants, payments, refunds, payouts, batches);
        out.printf("  MySQL ledger written in %.1f s (each payment = 1 journal entry + 2-3 postings)%n", seconds(step));

        step = System.nanoTime();
        BankFileWriter.Summary files = new BankFileWriter(data, o, rnd).write(payments, batches);
        out.printf("  %,d bank files written (%,d lines) in %.1f s -> %s%n", files.files(), files.lines(), seconds(step),
                data.resolve("bank_files"));

        out.println();
        out.println("Problems planted in the bank files (the answer key is data/answer_key.csv):");
        TablePrinter table = new TablePrinter("Planted", "Count").alignRight(1);
        files.planted().forEach((type, n) -> table.add(type, String.format("%,d", n)));
        table.print(out);
        out.printf("%nDone in %.1f s. Next: ./payrecon reconcile --period %s --through %s%n", seconds(t0), o.month(),
                o.month().atEndOfMonth());
    }

    private void requireEmptyLedger() throws SQLException {
        try (Connection c = db.connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM payment WHERE payment_id LIKE 'pay\\_0%'")) {
            rs.next();
            if (rs.getLong(1) > 0) {
                throw new ApiException(409, "ledger_not_empty",
                        "The ledger already has generated payments. Start fresh with: ./payrecon init --reset --yes");
            }
        }
    }

    // ------------------------------------------------------------------ business data (in memory)

    private List<Merchant> merchants(Options o, Random rnd) {
        List<Merchant> list = new ArrayList<>();
        for (int i = 1; i <= o.merchants(); i++) {
            Merchant m = new Merchant();
            m.id = String.format("M%05d", i);
            m.name = NAME_A[rnd.nextInt(NAME_A.length)] + " " + NAME_B[rnd.nextInt(NAME_B.length)];
            m.feeBps = 150 + rnd.nextInt(101);
            m.cycle = rnd.nextDouble() < 0.25 ? "DAILY" : "WEEKLY";
            m.payoutBank = rnd.nextDouble() < 0.6 ? "HDFC" : "ICICI";
            list.add(m);
        }
        return list;
    }

    private List<Payment> payments(Options o, Random rnd, List<Merchant> merchants) {
        // Zipf-like popularity: a few big merchants, a long tail of small ones (like real life)
        double[] cumulative = new double[merchants.size()];
        double total = 0;
        for (int i = 0; i < merchants.size(); i++) {
            total += 1.0 / Math.pow(i + 1, 0.9);
            cumulative[i] = total;
        }
        int hourTotal = 0;
        for (int w : HOUR_WEIGHTS) {
            hourTotal += w;
        }
        int days = o.month().lengthOfMonth();
        List<Payment> list = new ArrayList<>(o.payments());
        for (int i = 0; i < o.payments(); i++) {
            Payment p = new Payment();
            p.merchant = merchants.get(pick(cumulative, rnd.nextDouble() * total));
            double m = rnd.nextDouble();
            p.method = m < 0.60 ? "UPI" : m < 0.85 ? "CARD" : "NETBANKING";
            p.acquirer = "CARD".equals(p.method) ? "CARDNET" : (rnd.nextDouble() < 0.55 ? "HDFC" : "ICICI");
            p.amount = amount(p.method, rnd);
            p.fee = Money.basisPoints(p.amount, p.merchant.feeBps);
            int hour = pickHour(rnd, hourTotal);
            p.at = o.month().atDay(1 + rnd.nextInt(days))
                    .atTime(hour, rnd.nextInt(60), rnd.nextInt(60), rnd.nextInt(1000) * 1_000_000);
            list.add(p);
        }
        list.sort(Comparator.comparing((Payment p) -> p.at));
        for (int i = 0; i < list.size(); i++) {
            list.get(i).id = String.format("pay_%010d", i + 1);
        }
        return list;
    }

    private static long amount(String method, Random rnd) {
        if ("UPI".equals(method) && rnd.nextDouble() < 0.35) {
            return ROUND_RUPEES[rnd.nextInt(ROUND_RUPEES.length)] * 100; // UPI users love round amounts
        }
        double mu = "UPI".equals(method) ? 6.0 : 7.0; // median about ₹400 for UPI, ₹1,100 for cards
        double rupees = Math.exp(mu + rnd.nextGaussian());
        rupees = Math.max(10, Math.min(rupees, 200_000));
        return Math.round(rupees * 100);
    }

    private static int pick(double[] cumulative, double target) {
        int lo = 0;
        int hi = cumulative.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cumulative[mid] < target) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static int pickHour(Random rnd, int hourTotal) {
        int r = rnd.nextInt(hourTotal);
        for (int h = 0; h < HOUR_WEIGHTS.length; h++) {
            r -= HOUR_WEIGHTS[h];
            if (r < 0) {
                return h;
            }
        }
        return 23;
    }

    private List<Refund> refunds(Options o, Random rnd, List<Payment> payments) {
        LocalDateTime monthEnd = o.month().atEndOfMonth().atTime(23, 59, 59);
        List<Refund> list = new ArrayList<>();
        for (Payment p : payments) {
            if (rnd.nextDouble() >= 0.02) {
                continue;
            }
            LocalDateTime at = p.at.plusDays(1 + rnd.nextInt(5)).plusMinutes(rnd.nextInt(600));
            if (at.isAfter(monthEnd)) {
                continue;
            }
            long amount = rnd.nextBoolean() ? p.amount
                    : Math.max(100, Math.round(p.amount * (0.1 + 0.8 * rnd.nextDouble())));
            Refund r = new Refund();
            r.payment = p;
            r.amount = Math.min(amount, p.amount);
            r.at = at;
            p.refunded += r.amount;
            list.add(r);
        }
        list.sort(Comparator.comparing((Refund r) -> r.at));
        for (int i = 0; i < list.size(); i++) {
            list.get(i).id = String.format("rfd_%010d", i + 1);
        }
        return list;
    }

    /**
     * DAILY merchants are paid every day for the previous day's net; WEEKLY merchants every Monday.
     * Net = payments minus our fee minus refunds. If net is not positive, it carries forward.
     * All payouts to the same bank on the same day travel as one BATCH (one bank debit).
     */
    private List<Payout> payouts(Options o, List<Merchant> merchants, List<Payment> payments, List<Refund> refunds,
                                 List<Batch> batchesOut) {
        Map<Merchant, List<Payment>> pays = new HashMap<>();
        for (Payment p : payments) {
            pays.computeIfAbsent(p.merchant, k -> new ArrayList<>()).add(p);
        }
        Map<Merchant, List<Refund>> refs = new HashMap<>();
        for (Refund r : refunds) {
            refs.computeIfAbsent(r.payment.merchant, k -> new ArrayList<>()).add(r);
        }
        Map<Merchant, int[]> cursor = new HashMap<>();
        Map<Merchant, Long> carry = new HashMap<>();
        Map<Merchant, List<Payment>> pendingPayments = new HashMap<>();
        Map<Merchant, List<Refund>> pendingRefunds = new HashMap<>();
        Map<String, Batch> batches = new LinkedHashMap<>();
        List<Payout> payouts = new ArrayList<>();

        for (int day = 2; day <= o.month().lengthOfMonth(); day++) {
            LocalDate date = o.month().atDay(day);
            LocalDateTime cutoff = date.atStartOfDay();
            boolean monday = date.getDayOfWeek() == DayOfWeek.MONDAY;
            String stamp = date.format(DateTimeFormatter.BASIC_ISO_DATE);
            for (Merchant m : merchants) {
                if (!"DAILY".equals(m.cycle) && !monday) {
                    continue;
                }
                int[] cur = cursor.computeIfAbsent(m, k -> new int[2]);
                List<Payment> mp = pays.getOrDefault(m, List.of());
                List<Refund> mr = refs.getOrDefault(m, List.of());
                List<Payment> pp = pendingPayments.computeIfAbsent(m, k -> new ArrayList<>());
                List<Refund> pr = pendingRefunds.computeIfAbsent(m, k -> new ArrayList<>());
                long net = carry.getOrDefault(m, 0L);
                while (cur[0] < mp.size() && mp.get(cur[0]).at.isBefore(cutoff)) {
                    Payment p = mp.get(cur[0]++);
                    pp.add(p);
                    net += p.amount - p.fee;
                }
                while (cur[1] < mr.size() && mr.get(cur[1]).at.isBefore(cutoff)) {
                    Refund r = mr.get(cur[1]++);
                    pr.add(r);
                    net -= r.amount;
                }
                if (net <= 0 || (pp.isEmpty() && pr.isEmpty())) {
                    carry.put(m, net);
                    continue;
                }
                Payout po = new Payout();
                po.id = "po_" + stamp + "_" + m.id;
                po.merchant = m;
                po.amount = net;
                po.date = date;
                po.batchId = "pob_" + stamp + "_" + m.payoutBank;
                Batch b = batches.computeIfAbsent(po.batchId, id -> {
                    Batch nb = new Batch();
                    nb.id = id;
                    nb.bank = m.payoutBank;
                    nb.date = date;
                    return nb;
                });
                b.total += net;
                b.count++;
                for (Payment p : pp) {
                    p.payoutId = po.id;
                }
                for (Refund r : pr) {
                    r.payoutId = po.id;
                }
                pp.clear();
                pr.clear();
                carry.put(m, 0L);
                payouts.add(po);
            }
        }
        batchesOut.addAll(batches.values());
        return payouts;
    }

    // ------------------------------------------------------------------ ledger (bulk path)

    /**
     * Bulk-loads the month into MySQL. Same journal entries as the API (LedgerService.paymentLegs etc.),
     * but written in batches of 5,000 with explicit entry ids, and balances updated once at the end:
     * hundreds of thousands of rows in seconds instead of one transaction per payment.
     */
    private void writeLedger(List<Merchant> merchants, List<Payment> payments, List<Refund> refunds,
                             List<Payout> payouts, List<Batch> batches) throws SQLException {
        db.inTransaction(c -> {
            ledger.ensureSystemAccounts(c);
            for (Merchant m : merchants) {
                ledger.ensureMerchant(c, m.id, m.name, m.feeBps, m.cycle, m.payoutBank);
            }
            return null;
        });

        try (Connection c = db.connect()) {
            c.setAutoCommit(false);
            Map<String, long[]> accounts = new HashMap<>(); // code -> {account_id, 1 if debit-normal}
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT code, account_id, type FROM account")) {
                while (rs.next()) {
                    accounts.put(rs.getString(1), new long[] {rs.getLong(2),
                        AccountType.valueOf(rs.getString(3)).debitNormal() ? 1 : 0});
                }
            }
            long nextEntry;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(entry_id), 0) + 1 FROM journal_entry")) {
                rs.next();
                nextEntry = rs.getLong(1);
            }
            Map<Long, Long> deltas = new TreeMap<>();
            try (BulkJournal journal = new BulkJournal(c, accounts, deltas, nextEntry)) {
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO payment (payment_id, merchant_id, amount_minor, fee_minor, refunded_minor, method,"
                                + " acquirer, captured_at, payout_id, entry_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    int n = 0;
                    for (Payment p : payments) {
                        long entryId = journal.entry("PAYMENT_CAPTURED", p.id, p.at,
                                LedgerService.paymentLegs(p.merchant.id, p.acquirer, p.amount, p.fee));
                        ins.setString(1, p.id);
                        ins.setString(2, p.merchant.id);
                        ins.setLong(3, p.amount);
                        ins.setLong(4, p.fee);
                        ins.setLong(5, p.refunded);
                        ins.setString(6, p.method);
                        ins.setString(7, p.acquirer);
                        ins.setObject(8, p.at);
                        ins.setString(9, p.payoutId);
                        ins.setLong(10, entryId);
                        ins.addBatch();
                        if (++n % CHUNK == 0) {
                            journal.flush();
                            ins.executeBatch();
                            c.commit();
                        }
                    }
                    journal.flush();
                    ins.executeBatch();
                    c.commit();
                }

                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO refund (refund_id, payment_id, amount_minor, created_at, payout_id, entry_id)"
                                + " VALUES (?, ?, ?, ?, ?, ?)")) {
                    for (Refund r : refunds) {
                        long entryId = journal.entry("REFUND", r.id, r.at,
                                LedgerService.refundLegs(r.payment.merchant.id, r.amount));
                        ins.setString(1, r.id);
                        ins.setString(2, r.payment.id);
                        ins.setLong(3, r.amount);
                        ins.setObject(4, r.at);
                        ins.setString(5, r.payoutId);
                        ins.setLong(6, entryId);
                        ins.addBatch();
                    }
                    journal.flush();
                    ins.executeBatch();
                    c.commit();
                }

                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO payout_batch (batch_id, bank, payout_date, total_minor, payout_count) VALUES (?, ?, ?, ?, ?)")) {
                    for (Batch b : batches) {
                        ins.setString(1, b.id);
                        ins.setString(2, b.bank);
                        ins.setObject(3, b.date);
                        ins.setLong(4, b.total);
                        ins.setInt(5, b.count);
                        ins.addBatch();
                    }
                    ins.executeBatch();
                    c.commit();
                }

                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO payout (payout_id, merchant_id, batch_id, amount_minor, payout_date, entry_id)"
                                + " VALUES (?, ?, ?, ?, ?, ?)")) {
                    for (Payout po : payouts) {
                        long entryId = journal.entry("PAYOUT", po.id, po.date.atTime(10, 0),
                                LedgerService.payoutLegs(po.merchant.id, po.amount));
                        ins.setString(1, po.id);
                        ins.setString(2, po.merchant.id);
                        ins.setString(3, po.batchId);
                        ins.setLong(4, po.amount);
                        ins.setObject(5, po.date);
                        ins.setLong(6, entryId);
                        ins.addBatch();
                    }
                    journal.flush();
                    ins.executeBatch();
                    c.commit();
                }
            }

            try (PreparedStatement up = c.prepareStatement(
                    "UPDATE account SET balance_minor = balance_minor + ? WHERE account_id = ?")) {
                for (Map.Entry<Long, Long> e : deltas.entrySet()) {
                    up.setLong(1, e.getValue());
                    up.setLong(2, e.getKey());
                    up.addBatch();
                }
                up.executeBatch();
            }
            c.commit();
        }
        ledger.clearCache();
    }

    /** Batches journal_entry + posting inserts and adds up the balance change of every account. */
    private static final class BulkJournal implements AutoCloseable {
        private final PreparedStatement insEntry;
        private final PreparedStatement insPosting;
        private final Map<String, long[]> accounts;
        private final Map<Long, Long> deltas;
        private long nextEntry;

        BulkJournal(Connection c, Map<String, long[]> accounts, Map<Long, Long> deltas, long nextEntry) throws SQLException {
            this.insEntry = c.prepareStatement(
                    "INSERT INTO journal_entry (entry_id, entry_type, external_ref, created_at) VALUES (?, ?, ?, ?)");
            this.insPosting = c.prepareStatement(
                    "INSERT INTO posting (entry_id, account_id, direction, amount_minor) VALUES (?, ?, ?, ?)");
            this.accounts = accounts;
            this.deltas = deltas;
            this.nextEntry = nextEntry;
        }

        long entry(String type, String ref, LocalDateTime at, List<Leg> legs) throws SQLException {
            LedgerService.validateBalanced(legs);
            long id = nextEntry++;
            insEntry.setLong(1, id);
            insEntry.setString(2, type);
            insEntry.setString(3, ref);
            insEntry.setObject(4, at);
            insEntry.addBatch();
            for (Leg leg : legs) {
                long[] account = accounts.get(leg.accountCode());
                if (account == null) {
                    throw new IllegalStateException("Unknown account " + leg.accountCode());
                }
                insPosting.setLong(1, id);
                insPosting.setLong(2, account[0]);
                insPosting.setString(3, leg.direction().name());
                insPosting.setLong(4, leg.amountMinor());
                insPosting.addBatch();
                boolean debitNormal = account[1] == 1;
                long delta = (leg.direction() == Direction.DEBIT) == debitNormal ? leg.amountMinor() : -leg.amountMinor();
                deltas.merge(account[0], delta, Long::sum);
            }
            return id;
        }

        void flush() throws SQLException {
            insEntry.executeBatch(); // entries first: postings and payments point at them (foreign keys)
            insPosting.executeBatch();
        }

        @Override
        public void close() throws SQLException {
            insEntry.close();
            insPosting.close();
        }
    }

    private static double seconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1e9;
    }
}
