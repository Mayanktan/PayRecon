package com.payrecon.recon;

import com.payrecon.common.ApiException;
import com.payrecon.common.FileUtil;
import com.payrecon.common.Money;
import com.payrecon.common.TablePrinter;
import com.payrecon.common.Tsv;
import com.payrecon.config.Config;
import com.payrecon.db.Db;
import com.payrecon.hive.HiveRunner;
import com.payrecon.ledger.LedgerService;
import com.payrecon.mongo.MongoStore;
import com.payrecon.recon.match.ExceptionType;
import com.payrecon.recon.match.Rows;
import com.payrecon.recon.mr.ReconJobs;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * One reconciliation run, start to finish:
 *  1. extract the period's payments and payouts from MySQL into files
 *  2. run the three MapReduce matching jobs over the ledger files and the raw bank files
 *  3. load exceptions into MySQL (the analysts' work queue)
 *  4. cash application: book the money banks actually paid into the ledger
 *  5. publish result files to the Hive warehouse folders and refresh Hive partitions
 *  6. upsert raw bank records and merchant payout statements into MongoDB
 * The runtime clock starts BEFORE any input is read (a bug fixed from the NASA project).
 */
public final class ReconOrchestrator {

    private final Config config;
    private final Db db;
    private final LedgerService ledger;

    public ReconOrchestrator(Config config, Db db, LedgerService ledger) {
        this.config = config;
        this.db = db;
        this.ledger = ledger;
    }

    /** Everything learned while loading MapReduce output. */
    static final class LoadResult {
        final Map<String, Integer> passCount = new LinkedHashMap<>();
        final Map<String, Long> passAmount = new LinkedHashMap<>();
        final Map<String, Integer> typeCount = new TreeMap<>();
        final Map<String, Long> typeAmount = new TreeMap<>();
        final List<CashApplication.Line> cashLines = new ArrayList<>();
        final Map<String, String> batchStatus = new HashMap<>();
        int matched;
        int exceptions;
        int actionable;
        int malformed;
    }

    public int run(YearMonth period, LocalDate through, boolean skipHive, boolean skipMongo, PrintStream out)
            throws Exception {
        long startNanos = System.nanoTime();
        Map<String, Long> timings = new LinkedHashMap<>();
        Path data = config.dataDir();
        int runId = createRun(period, through);
        out.printf("%nReconciliation run #%d  ·  period %s  ·  bank files dated up to %s%n%n", runId, period, through);
        Path work = data.resolve("work").resolve("run_" + runId);
        FileUtil.deleteRecursively(work);
        try {
            long step = System.nanoTime();
            LedgerExtractor.Counts counts = LedgerExtractor.extract(db, period, through,
                    work.resolve("ledger_payments"), work.resolve("ledger_payouts"));
            if (counts.payments() == 0) {
                throw new ApiException(400, "no_payments",
                        "The ledger has no payments in " + period + ". Run ./payrecon generate first.");
            }
            List<Path> bankFiles = BankFiles.upTo(data.resolve("bank_files"), through);
            if (bankFiles.isEmpty()) {
                throw new ApiException(400, "no_bank_files",
                        "No bank files dated up to " + through + ". Run ./payrecon generate first.");
            }
            timings.put("Extract ledger from MySQL", millisSince(step));
            out.printf("  [1/6] MySQL -> files: %,d payments and %,d payouts; %d bank files to read%n",
                    counts.payments(), counts.payouts(), bankFiles.size());

            step = System.nanoTime();
            out.println("  [2/6] MapReduce: job 1 (exact reference), then jobs 2 (amount + date window)"
                    + " and 3 (payout batches) in parallel ...");
            ReconJobs.Outcome mr = ReconJobs.run(ReconJobs.configuration(through), new ReconJobs.Inputs(
                    work.resolve("ledger_payments"), work.resolve("ledger_payouts"), bankFiles,
                    work.resolve("pass1"), work.resolve("pass2"), work.resolve("pass3")));
            timings.put("MapReduce (3 jobs)", millisSince(step));
            out.printf("        job 1 took %.1f s; jobs 2 + 3 together took %.1f s%n",
                    mr.pass1Millis() / 1000.0, mr.pass2and3Millis() / 1000.0);

            step = System.nanoTime();
            LoadResult load = loadIntoMySql(runId, work);
            timings.put("Load exceptions into MySQL", millisSince(step));
            out.printf("  [3/6] MySQL: loaded %,d exceptions (%,d need action)%n", load.exceptions, load.actionable);

            step = System.nanoTime();
            CashApplication.Result cash = CashApplication.apply(db, ledger, runId, load.cashLines);
            timings.put("Cash application (ledger)", millisSince(step));
            out.printf("  [4/6] Ledger: %,d new bank credits booked (%s to clearing, %s to suspense); %,d already booked%n",
                    cash.newLines(), Money.inr(cash.newToClearing()), Money.inr(cash.newToSuspense()), cash.unchanged());
            if (cash.movedToClearing() > 0 || cash.movedToSuspense() > 0) {
                out.printf("        %d lines identified since the last run moved suspense -> clearing (%s); %d moved back (%s)%n",
                        cash.movedToClearing(), Money.inr(cash.movedToClearingMinor()), cash.movedToSuspense(),
                        Money.inr(cash.movedToSuspenseMinor()));
            }

            step = System.nanoTime();
            publishWarehouse(data.resolve("warehouse"), runId, work);
            String hive = skipHive ? "skipped (--skip-hive)" : refreshHive();
            timings.put("Publish to Hive warehouse", millisSince(step));
            out.println("  [5/6] Hive: " + hive);

            step = System.nanoTime();
            String mongo = skipMongo ? "skipped (--skip-mongo)" : updateMongo(bankFiles, period, through, load.batchStatus);
            timings.put("MongoDB (raw records + statements)", millisSince(step));
            out.println("  [6/6] MongoDB: " + mongo);

            long runtime = millisSince(startNanos);
            List<TieOut.Row> tieOut;
            try (Connection c = db.connect()) {
                tieOut = TieOut.check(c, runId, period, through);
            }
            completeRun(runId, counts, bankFiles.size(), mr, load, runtime, timings);
            printSummary(out, runId, counts, mr, load, tieOut, timings, runtime);
            return runId;
        } catch (Exception e) {
            failRun(runId);
            throw e;
        }
    }

    // ------------------------------------------------------------------ step 3: load results

    private LoadResult loadIntoMySql(int runId, Path work) throws IOException, SQLException {
        LoadResult r = new LoadResult();
        for (int pass = 1; pass <= 3; pass++) {
            for (Path file : FileUtil.partFiles(work.resolve("pass" + pass).resolve("matched"))) {
                for (String line : Files.readAllLines(file)) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    String[] f = Tsv.split(line);
                    String matchPass = f[0];
                    long bankAmount = Long.parseLong(f[6]);
                    r.passCount.merge(matchPass, 1, Integer::sum);
                    r.passAmount.merge(matchPass, bankAmount, Long::sum);
                    r.matched++;
                    if (Rows.PASS_PAYOUT.equals(matchPass)) {
                        r.batchStatus.putIfAbsent(f[1], "MATCHED");
                    } else {
                        r.cashLines.add(new CashApplication.Line(f[2], f[3], LocalDate.parse(f[8]), bankAmount,
                                CashApplication.Bucket.CLEARING));
                    }
                }
            }
        }

        List<String[]> rows = new ArrayList<>();
        for (int pass = 1; pass <= 3; pass++) {
            for (Path file : FileUtil.partFiles(work.resolve("pass" + pass).resolve("exceptions"))) {
                for (String line : Files.readAllLines(file)) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    String[] f = Tsv.split(line);
                    rows.add(f);
                    if (pass == 3 && f[0].startsWith("PAYOUT_")) {
                        r.batchStatus.put(f[2], f[0]);
                    }
                }
            }
        }

        // unreadable bank lines become exceptions too (never silently dropped)
        Path malformedOut = work.resolve("malformed_exceptions");
        Files.createDirectories(malformedOut);
        try (BufferedWriter w = Files.newBufferedWriter(malformedOut.resolve("part-00000"))) {
            for (Path file : FileUtil.partFiles(work.resolve("pass1").resolve("malformed"))) {
                for (String line : Files.readAllLines(file)) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    String[] f = Tsv.split(line); // line_id, bank, error, raw line
                    String lineId = f[0];
                    String fileName = lineId.substring(lineId.indexOf(':') + 1, lineId.lastIndexOf(':'));
                    String row = Rows.exception(ExceptionType.MALFORMED_BANK_LINE, null, lineId, f[1], null, null,
                            null, null, null, BankFiles.dateOf(fileName), "unreadable line: " + f[2]);
                    w.write(row);
                    w.newLine();
                    rows.add(Tsv.split(row));
                    r.malformed++;
                }
            }
        }

        for (String[] f : rows) {
            String type = f[0];
            Long bankAmount = Tsv.lng(f[7]);
            boolean creditDuplicate = "DUPLICATE_IN_BANK".equals(type) && f[2].startsWith("pay_");
            boolean unidentifiedCash = "MISSING_IN_LEDGER".equals(type) || "AMBIGUOUS_MATCH".equals(type) || creditDuplicate;
            if (bankAmount != null && unidentifiedCash) {
                r.cashLines.add(new CashApplication.Line(f[3], f[4], Tsv.date(f[10]), bankAmount,
                        CashApplication.Bucket.SUSPENSE));
            }
            r.typeCount.merge(type, 1, Integer::sum);
            r.typeAmount.merge(type, Math.abs(amountOf(f)), Long::sum);
            if (Boolean.parseBoolean(f[1])) {
                r.actionable++;
            }
        }
        r.exceptions = rows.size();

        // Queue order: actionable first, then biggest amount first. Inserting in this order makes
        // exception_id order = priority order, so the analysts' queue can walk an index (see ExceptionWorkflow).
        rows.sort(Comparator.comparing((String[] f) -> !Boolean.parseBoolean(f[1]))
                .thenComparingLong(f -> -Math.abs(amountOf(f))));
        insertExceptions(runId, rows);
        return r;
    }

    private static long amountOf(String[] f) {
        Long diff = Tsv.lng(f[8]);
        if (diff != null) {
            return diff;
        }
        Long ledgerAmount = Tsv.lng(f[6]);
        if (ledgerAmount != null) {
            return ledgerAmount;
        }
        Long bankAmount = Tsv.lng(f[7]);
        return bankAmount == null ? 0 : bankAmount;
    }

    private void insertExceptions(int runId, List<String[]> rows) throws SQLException {
        db.inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO recon_exception (run_id, exception_type, actionable, ledger_ref, bank_line_id, bank,"
                            + " merchant_id, ledger_amount_minor, bank_amount_minor, diff_minor, ledger_date, bank_date, note)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                int n = 0;
                for (String[] f : rows) {
                    ps.setInt(1, runId);
                    ps.setString(2, f[0]);
                    ps.setBoolean(3, Boolean.parseBoolean(f[1]));
                    ps.setString(4, Tsv.str(f[2]));
                    ps.setString(5, Tsv.str(f[3]));
                    ps.setString(6, Tsv.str(f[4]));
                    ps.setString(7, Tsv.str(f[5]));
                    setLong(ps, 8, Tsv.lng(f[6]));
                    setLong(ps, 9, Tsv.lng(f[7]));
                    setLong(ps, 10, Tsv.lng(f[8]));
                    setDate(ps, 11, Tsv.date(f[9]));
                    setDate(ps, 12, Tsv.date(f[10]));
                    String note = Tsv.str(f[11]);
                    ps.setString(13, note == null || note.length() <= 255 ? note : note.substring(0, 255));
                    ps.addBatch();
                    if (++n % 2_000 == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    private static void setLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setDate(PreparedStatement ps, int index, LocalDate value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DATE);
        } else {
            ps.setObject(index, value);
        }
    }

    // ------------------------------------------------------------------ step 5: Hive

    public static void ensureWarehouseDirs(Path warehouse) throws IOException {
        for (String table : List.of("ledger_payments", "bank_lines", "recon_matches", "recon_exceptions")) {
            Files.createDirectories(warehouse.resolve(table));
        }
    }

    /** Lays the result files out as Hive partitions: table/run_id=N/... and bank_lines/value_date=D/... */
    static void publishWarehouse(Path warehouse, int runId, Path work) throws IOException {
        ensureWarehouseDirs(warehouse);
        Path ledgerDir = warehouse.resolve("ledger_payments").resolve("run_id=" + runId);
        Path matchesDir = warehouse.resolve("recon_matches").resolve("run_id=" + runId);
        Path exceptionsDir = warehouse.resolve("recon_exceptions").resolve("run_id=" + runId);
        for (Path dir : List.of(ledgerDir, matchesDir, exceptionsDir)) {
            FileUtil.deleteRecursively(dir);
            Files.createDirectories(dir);
        }
        FileUtil.copyParts(work.resolve("ledger_payments"), ledgerDir, "");
        for (int pass = 1; pass <= 3; pass++) {
            FileUtil.copyParts(work.resolve("pass" + pass).resolve("matched"), matchesDir, "p" + pass + "-");
            FileUtil.copyParts(work.resolve("pass" + pass).resolve("exceptions"), exceptionsDir, "p" + pass + "-");
        }
        FileUtil.copyParts(work.resolve("malformed_exceptions"), exceptionsDir, "mf-");

        // A bank line is a fact about its date, the same in every run, so each date's partition is replaced.
        Path normalized = work.resolve("pass1").resolve("bank_normalized");
        if (Files.isDirectory(normalized)) {
            List<Path> dateDirs;
            try (Stream<Path> stream = Files.list(normalized)) {
                dateDirs = stream.filter(Files::isDirectory).toList();
            }
            for (Path dateDir : dateDirs) {
                Path target = warehouse.resolve("bank_lines").resolve(dateDir.getFileName().toString());
                FileUtil.deleteRecursively(target);
                FileUtil.copyParts(dateDir, target, "");
            }
        }
    }

    private String refreshHive() {
        try {
            HiveRunner.Result r = new HiveRunner(config).script("01_create_tables.hql", "02_refresh_partitions.hql", Map.of());
            if (r.ok()) {
                return "partitions refreshed (ledger_payments, bank_lines, recon_matches, recon_exceptions)";
            }
            return "NOT refreshed: " + firstError(r.stderr()) + "  ->  when Hive is up, run: ./payrecon report refresh";
        } catch (Exception e) {
            return "NOT refreshed: " + e.getMessage() + "  ->  when Hive is up, run: ./payrecon report refresh";
        }
    }

    public static String firstError(String stderr) {
        for (String line : stderr.split("\n")) {
            if (line.contains("Error") || line.contains("ERROR") || line.contains("refused")) {
                return line.trim().length() > 160 ? line.trim().substring(0, 160) : line.trim();
            }
        }
        return "beeline failed";
    }

    // ------------------------------------------------------------------ step 6: MongoDB

    private String updateMongo(List<Path> bankFiles, YearMonth period, LocalDate through,
                               Map<String, String> batchStatus) {
        try (MongoStore mongo = new MongoStore(config)) {
            mongo.ensureIndexes();
            long raw = mongo.ingestBankFiles(bankFiles);
            int statements = StatementBuilder.build(db, mongo, period, through, batchStatus);
            return String.format("%,d raw bank records and %,d payout statements upserted", raw, statements);
        } catch (Exception e) {
            return "NOT updated: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------ run bookkeeping

    private int createRun(YearMonth period, LocalDate through) throws SQLException {
        try (Connection c = db.connect();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO recon_run (period, through_date, status) VALUES (?, ?, 'RUNNING')",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, period.toString());
            ps.setObject(2, through);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void completeRun(int runId, LedgerExtractor.Counts counts, int bankFiles, ReconJobs.Outcome mr,
                             LoadResult load, long runtimeMs, Map<String, Long> timings) throws SQLException {
        db.inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE recon_run SET status = 'COMPLETED', finished_at = CURRENT_TIMESTAMP(3), runtime_ms = ?,"
                            + " ledger_payments = ?, ledger_payouts = ?, bank_files = ?, bank_lines = ?, malformed_lines = ?,"
                            + " matched = ?, exceptions_total = ?, exceptions_actionable = ? WHERE run_id = ?")) {
                ps.setLong(1, runtimeMs);
                ps.setInt(2, counts.payments());
                ps.setInt(3, counts.payouts());
                ps.setInt(4, bankFiles);
                ps.setLong(5, mr.counters().getOrDefault("BANK_LINES", 0L));
                ps.setInt(6, load.malformed);
                ps.setInt(7, load.matched);
                ps.setInt(8, load.exceptions);
                ps.setInt(9, load.actionable);
                ps.setInt(10, runId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO recon_pass_stat (run_id, pass_name, matched, amount_minor) VALUES (?, ?, ?, ?)")) {
                for (Map.Entry<String, Integer> e : load.passCount.entrySet()) {
                    ps.setInt(1, runId);
                    ps.setString(2, e.getKey());
                    ps.setInt(3, e.getValue());
                    ps.setLong(4, load.passAmount.getOrDefault(e.getKey(), 0L));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO recon_step_timing (run_id, step, millis) VALUES (?, ?, ?)")) {
                for (Map.Entry<String, Long> e : timings.entrySet()) {
                    ps.setInt(1, runId);
                    ps.setString(2, e.getKey());
                    ps.setLong(3, e.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    private void failRun(int runId) {
        try (Connection c = db.connect();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE recon_run SET status = 'FAILED', finished_at = CURRENT_TIMESTAMP(3) WHERE run_id = ?")) {
            ps.setInt(1, runId);
            ps.executeUpdate();
        } catch (SQLException ignored) {
            // the original error is more useful than this one
        }
    }

    // ------------------------------------------------------------------ summary

    private void printSummary(PrintStream out, int runId, LedgerExtractor.Counts counts, ReconJobs.Outcome mr,
                              LoadResult load, List<TieOut.Row> tieOut, Map<String, Long> timings, long runtime) {
        Map<String, Long> k = mr.counters();
        out.println();
        out.printf("Bank lines read: %,d  (HDFC %,d · ICICI %,d · CARDNET %,d)  ·  unreadable lines: %,d%n",
                k.get("BANK_LINES"), k.get("BANK_LINES_HDFC"), k.get("BANK_LINES_ICICI"), k.get("BANK_LINES_CARDNET"),
                k.get("MALFORMED_LINES"));

        out.println();
        out.println("MATCHING");
        TablePrinter passes = new TablePrinter("Pass", "Rule", "Matched", "Amount").alignRight(2, 3);
        passes.add("1 " + Rows.PASS_EXACT, "same payment reference", count(load.passCount, Rows.PASS_EXACT),
                Money.inr(load.passAmount.getOrDefault(Rows.PASS_EXACT, 0L)));
        passes.add("2 " + Rows.PASS_WINDOW, "same amount + nearby date (short or missing reference)",
                count(load.passCount, Rows.PASS_WINDOW), Money.inr(load.passAmount.getOrDefault(Rows.PASS_WINDOW, 0L)));
        passes.add("3 " + Rows.PASS_PAYOUT, "one bank debit = one whole payout batch",
                count(load.passCount, Rows.PASS_PAYOUT), Money.inr(load.passAmount.getOrDefault(Rows.PASS_PAYOUT, 0L)));
        passes.print(out);
        long paymentsMatched = load.passCount.getOrDefault(Rows.PASS_EXACT, 0)
                + load.passCount.getOrDefault(Rows.PASS_WINDOW, 0);
        long inTransit = load.typeCount.getOrDefault(ExceptionType.IN_TRANSIT.name(), 0);
        long due = counts.payments() - inTransit;
        out.printf("Auto-matched %.2f%% of the %,d payments due to settle by the cutoff"
                        + " (%,d more are still in transit: normal, not an error)%n",
                due == 0 ? 100.0 : 100.0 * paymentsMatched / due, due, inTransit);

        out.println();
        out.println("EXCEPTIONS");
        TablePrinter ex = new TablePrinter("Type", "Needs action", "Count", "Amount", "Meaning").alignRight(2, 3);
        for (ExceptionType t : ExceptionType.values()) {
            Integer n = load.typeCount.get(t.name());
            if (n != null) {
                ex.add(t.name(), t.actionable ? "yes" : "no", String.format("%,d", n),
                        Money.inr(load.typeAmount.getOrDefault(t.name(), 0L)), t.meaning);
            }
        }
        ex.print(out);

        out.println();
        out.println("LEDGER TIE-OUT  (captured - cash booked) must equal the open items, for every bank");
        TablePrinter tie = new TablePrinter("Bank", "Captured", "Settled (cash booked)", "Still owed", "Open items", "Result")
                .alignRight(1, 2, 3, 4);
        for (TieOut.Row row : tieOut) {
            tie.add(row.bank(), Money.inr(row.captured()), Money.inr(row.settled()), Money.inr(row.stillOwed()),
                    Money.inr(row.openItems()),
                    row.ties() ? "TIES OUT" : "DIFFERENCE " + Money.inr(row.stillOwed() - row.openItems()));
        }
        tie.print(out);

        out.println();
        out.println("TIME PER STEP");
        TablePrinter time = new TablePrinter("Step", "Seconds").alignRight(1);
        timings.forEach((name, ms) -> time.add(name, String.format("%.1f", ms / 1000.0)));
        time.add("TOTAL (clock starts before reading any input)", String.format("%.1f", runtime / 1000.0));
        time.print(out);

        out.println();
        out.printf("Next:  ./payrecon verify --run %d   ·   ./payrecon report close --run %d   ·   ./payrecon exceptions list%n",
                runId, runId);
    }

    private static String count(Map<String, Integer> map, String key) {
        return String.format("%,d", map.getOrDefault(key, 0));
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
