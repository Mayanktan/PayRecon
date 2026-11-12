package com.payrecon;

import com.payrecon.api.ApiServer;
import com.payrecon.api.StressTest;
import com.payrecon.cli.Args;
import com.payrecon.common.ApiException;
import com.payrecon.common.FileUtil;
import com.payrecon.common.Money;
import com.payrecon.common.TablePrinter;
import com.payrecon.config.Config;
import com.payrecon.db.Db;
import com.payrecon.db.MySqlSchema;
import com.payrecon.exceptions.ExceptionWorkflow;
import com.payrecon.gen.DataGenerator;
import com.payrecon.hive.HiveRunner;
import com.payrecon.ledger.LedgerAudit;
import com.payrecon.ledger.LedgerService;
import com.payrecon.mongo.MongoStore;
import com.payrecon.recon.ReconOrchestrator;
import com.payrecon.recon.Verifier;
import org.bson.Document;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/** The PayRecon command-line tool. Run ./payrecon help for the list of commands. */
public final class Main {

    private static final PrintStream out = System.out;
    private static Config config;
    private static Db db;
    private static LedgerService ledger;

    private Main() {
    }

    public static void main(String[] argv) {
        Args a = Args.parse(argv);
        String cmd = a.pos(0, "help");
        int code;
        try {
            code = switch (cmd) {
                case "help", "--help", "-h" -> help();
                case "status" -> status();
                case "init" -> init(a);
                case "generate" -> generate(a);
                case "reconcile" -> reconcile(a);
                case "verify" -> verify(a);
                case "report" -> report(a);
                case "runs" -> runs();
                case "exceptions" -> exceptions(a);
                case "audit" -> audit();
                case "serve" -> serve(a);
                case "stress" -> stress(a);
                case "statement" -> statement(a);
                default -> {
                    System.err.println("Unknown command: " + cmd + "   (see ./payrecon help)");
                    yield 2;
                }
            };
        } catch (ApiException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            code = 1;
        } catch (SQLException e) {
            System.err.println("MySQL error: " + e.getMessage());
            System.err.println("Is MySQL running and initialised? Try: docker compose up -d   then   ./payrecon status");
            code = 1;
        } catch (Exception e) {
            System.err.println("Error: " + e);
            if (a.has("debug")) {
                e.printStackTrace();
            } else {
                System.err.println("(add --debug to see the full stack trace)");
            }
            code = 1;
        }
        System.exit(code); // Hadoop and the MongoDB driver leave background threads; exit explicitly
    }

    // ------------------------------------------------------------------ commands

    private static int help() {
        out.println("""
                PayRecon - Stripe-style payment ledger and bank reconciliation
                (MySQL ledger  ·  Hadoop MapReduce matching  ·  Hive analytics  ·  MongoDB documents)

                Setup
                  ./payrecon status                       check MySQL, MongoDB and Hive
                  ./payrecon init [--reset --yes]         create tables and indexes (--reset wipes ALL data)

                Data
                  ./payrecon generate [--month 2026-10] [--merchants 1000] [--payments 200000] [--seed 42]

                Reconciliation
                  ./payrecon reconcile [--period 2026-10] [--through 2026-10-31] [--skip-hive] [--skip-mongo]
                  ./payrecon verify [--run latest]        compare a run with the planted answer key
                  ./payrecon runs                         list reconciliation runs
                  ./payrecon report <close|aging|breakdown|lag|daily|all|refresh> [--run latest]   (Hive SQL)

                Exceptions queue (MySQL)
                  ./payrecon exceptions list [--run latest] [--status OPEN] [--type MISSING_IN_BANK] [--all] [--limit 20]
                  ./payrecon exceptions claim --analyst alice
                  ./payrecon exceptions show <id>
                  ./payrecon exceptions resolve <id> --analyst alice --resolution WRITE_OFF|BANK_CORRECTED|LEDGER_CORRECTED|NO_ACTION [--note "..."]
                  ./payrecon exceptions release <id> --analyst alice

                Ledger and API
                  ./payrecon audit                        double-entry checks on the whole ledger
                  ./payrecon serve [--port 8090]          start the REST API (Ctrl+C to stop)
                  ./payrecon stress [--url http://localhost:8090]   concurrency test against the running API
                  ./payrecon statement --merchant M00001 [--limit 3]   payout statements from MongoDB
                """);
        return 0;
    }

    private static int status() {
        Config cfg = config();
        boolean ok = true;
        out.println("MySQL    " + cfg.mysqlUrl().split("\\?")[0]);
        try (Connection c = db().connect(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT VERSION()")) {
                rs.next();
                out.println("         OK, version " + rs.getString(1));
            }
            try (ResultSet rs = st.executeQuery("SELECT (SELECT COUNT(*) FROM merchant), (SELECT COUNT(*) FROM payment),"
                    + " (SELECT COUNT(*) FROM recon_run)")) {
                rs.next();
                out.printf("         %,d merchants, %,d payments, %,d reconciliation runs%n", rs.getLong(1), rs.getLong(2), rs.getLong(3));
            } catch (SQLException e) {
                out.println("         tables not created yet -> run: ./payrecon init");
            }
        } catch (SQLException e) {
            ok = false;
            out.println("         NOT REACHABLE: " + e.getMessage());
        }
        out.println("MongoDB  " + cfg.mongoUri());
        try (MongoStore m = new MongoStore(cfg)) {
            m.ping();
            out.printf("         OK, %,d raw bank records, %,d payout statements%n", m.count(MongoStore.RAW), m.count(MongoStore.STATEMENTS));
        } catch (Exception e) {
            ok = false;
            out.println("         NOT REACHABLE: " + e.getMessage());
        }
        out.println("Hive     container " + cfg.hiveContainer() + ", jdbc:hive2://localhost:10000");
        boolean hive = new HiveRunner(cfg).isReady();
        out.println(hive ? "         OK, HiveServer2 answers queries" : "         NOT READY (it needs about a minute after docker compose up)");
        out.println("Data     " + cfg.dataDir());
        return ok && hive ? 0 : 1;
    }

    private static int init(Args a) throws Exception {
        boolean reset = a.has("reset");
        if (reset && !a.has("yes")) {
            out.print("This deletes ALL PayRecon data in MySQL, MongoDB and ./data. Type yes to continue: ");
            String answer = new Scanner(System.in).nextLine().trim();
            if (!"yes".equals(answer)) {
                out.println("Cancelled.");
                return 1;
            }
        }
        Config cfg = config();
        try (Connection c = db().connect()) {
            if (reset) {
                MySqlSchema.dropAll(c);
            }
            MySqlSchema.create(c);
            ledger().ensureSystemAccounts(c);
        }
        out.println("MySQL    15 tables and the system accounts are ready" + (reset ? " (wiped first)" : ""));
        try (MongoStore m = new MongoStore(cfg)) {
            if (reset) {
                m.dropAll();
            }
            m.ensureIndexes();
            out.println("MongoDB  collections and indexes are ready" + (reset ? " (wiped first)" : ""));
        } catch (Exception e) {
            out.println("MongoDB  NOT ready: " + e.getMessage());
        }
        Path data = cfg.dataDir();
        if (reset) {
            for (String dir : List.of("bank_files", "work", "warehouse")) {
                FileUtil.deleteRecursively(data.resolve(dir));
            }
            Files.deleteIfExists(data.resolve("answer_key.csv"));
        }
        ReconOrchestrator.ensureWarehouseDirs(data.resolve("warehouse"));
        HiveRunner hive = new HiveRunner(cfg);
        out.print("Hive     waiting for HiveServer2 ");
        boolean ready = false;
        for (int i = 0; i < 36 && !(ready = hive.isReady()); i++) {
            out.print(".");
            out.flush();
            Thread.sleep(5_000);
        }
        out.println();
        if (!ready) {
            out.println("Hive     NOT ready yet. When it is, run: ./payrecon report refresh");
            return 0;
        }
        HiveRunner.Result r = hive.script("01_create_tables.hql", "02_refresh_partitions.hql", Map.of());
        out.println(r.ok() ? "Hive     database payrecon with 4 external tables is ready"
                : "Hive     table creation failed: " + ReconOrchestrator.firstError(r.stderr()));
        return r.ok() ? 0 : 1;
    }

    private static int generate(Args a) throws Exception {
        YearMonth month = YearMonth.parse(a.flag("month", "2026-10"));
        int merchants = a.intFlag("merchants", 1_000);
        int payments = a.intFlag("payments", 200_000);
        if (merchants < 1 || payments < 1_000 || payments > 5_000_000) {
            throw new ApiException(400, "bad_size", "Use --merchants >= 1 and --payments between 1,000 and 5,000,000");
        }
        DataGenerator.Options o = new DataGenerator.Options(month, merchants, payments,
                Long.parseLong(a.flag("seed", "42")), a.intFlag("extra-days", 5));
        new DataGenerator(config(), db(), ledger()).run(o, out);
        return 0;
    }

    private static int reconcile(Args a) throws Exception {
        YearMonth period = YearMonth.parse(a.flag("period", "2026-10"));
        LocalDate through = LocalDate.parse(a.flag("through", period.atEndOfMonth().toString()));
        if (through.isBefore(period.atDay(1))) {
            throw new ApiException(400, "bad_cutoff", "--through must be on or after the start of the period");
        }
        new ReconOrchestrator(config(), db(), ledger()).run(period, through, a.has("skip-hive"), a.has("skip-mongo"), out);
        return 0;
    }

    private static int verify(Args a) throws Exception {
        int runId = workflow().resolveRunId(a.flag("run", "latest"));
        return Verifier.verify(db(), config().dataDir(), runId, out) ? 0 : 1;
    }

    private static int report(Args a) throws Exception {
        String which = a.pos(1, "close");
        HiveRunner hive = new HiveRunner(config());
        if ("refresh".equals(which)) {
            ReconOrchestrator.ensureWarehouseDirs(config().dataDir().resolve("warehouse"));
            HiveRunner.Result r = hive.script("01_create_tables.hql", "02_refresh_partitions.hql", Map.of());
            out.println(r.ok() ? "Hive tables and partitions refreshed" : "Failed: " + ReconOrchestrator.firstError(r.stderr()));
            return r.ok() ? 0 : 1;
        }
        Map<String, String[]> reports = new LinkedHashMap<>();
        reports.put("close", new String[] {"close_report.hql", "Month-end close per bank"});
        reports.put("aging", new String[] {"aging_report.hql", "Aging of unsettled payments"});
        reports.put("breakdown", new String[] {"exception_breakdown.hql", "Exception breakdown and top merchants"});
        reports.put("lag", new String[] {"settlement_lag.hql", "Settlement lag per bank"});
        reports.put("daily", new String[] {"daily_match_rate.hql", "Daily auto-match rate"});
        List<String> toRun = "all".equals(which) ? List.copyOf(reports.keySet()) : List.of(which);
        for (String name : toRun) {
            if (!reports.containsKey(name)) {
                throw new ApiException(400, "unknown_report", "Unknown report '" + name + "'. Use one of " + reports.keySet() + ", all, refresh");
            }
        }
        int runId = workflow().resolveRunId(a.flag("run", "latest"));
        LocalDate through;
        try (Connection c = db().connect();
             PreparedStatement ps = c.prepareStatement("SELECT through_date FROM recon_run WHERE run_id = ?")) {
            ps.setInt(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(404, "run_not_found", "No reconciliation run #" + runId);
                }
                through = rs.getObject(1, LocalDate.class);
            }
        }
        for (String name : toRun) {
            String[] r = reports.get(name);
            out.printf("%n== %s  (Hive, run #%d, hive/%s) ==%n", r[1], runId, r[0]);
            HiveRunner.Result result = hive.script(null, r[0], Map.of("run_id", String.valueOf(runId), "through", through.toString()));
            if (!result.ok()) {
                out.println("Hive query failed: " + ReconOrchestrator.firstError(result.stderr()));
                return 1;
            }
            out.print(result.stdout());
        }
        return 0;
    }

    private static int runs() throws SQLException {
        TablePrinter t = new TablePrinter("Run", "Period", "Bank files up to", "Status", "Payments", "Bank lines",
                "Matched", "Exceptions", "Need action", "Seconds").alignRight(0, 4, 5, 6, 7, 8, 9);
        try (Connection c = db().connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT run_id, period, through_date, status, ledger_payments, bank_lines, matched,"
                     + " exceptions_total, exceptions_actionable, runtime_ms FROM recon_run ORDER BY run_id")) {
            while (rs.next()) {
                long ms = rs.getLong(10);
                t.add(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), fmt(rs.getLong(5)),
                        fmt(rs.getLong(6)), fmt(rs.getLong(7)), fmt(rs.getLong(8)), fmt(rs.getLong(9)),
                        rs.wasNull() ? "" : String.format("%.1f", ms / 1000.0));
            }
        }
        if (t.isEmpty()) {
            out.println("No reconciliation runs yet. Run: ./payrecon reconcile");
        } else {
            t.print(out);
        }
        return 0;
    }

    private static int exceptions(Args a) throws Exception {
        String sub = a.pos(1, "list");
        ExceptionWorkflow wf = workflow();
        switch (sub) {
            case "list" -> wf.list(wf.resolveRunId(a.flag("run", "latest")), a.flag("status", null),
                    a.flag("type", null), a.has("all"), a.intFlag("limit", 20), out);
            case "claim" -> {
                String analyst = required(a, "analyst");
                Long id = wf.claim(wf.resolveRunId(a.flag("run", "latest")), analyst);
                out.println(id == null ? "The queue is empty."
                        : "Claimed exception #" + id + " for " + analyst + ". Look at it: ./payrecon exceptions show " + id);
            }
            case "show" -> {
                try (MongoStore m = new MongoStore(config())) {
                    wf.show(idArg(a), m, out);
                }
            }
            case "resolve" -> {
                long id = idArg(a);
                wf.resolve(id, required(a, "analyst"), required(a, "resolution"), a.flag("note", null));
                out.println("Resolved exception #" + id);
            }
            case "release" -> {
                long id = idArg(a);
                wf.release(id, required(a, "analyst"));
                out.println("Released exception #" + id + " back to the queue");
            }
            default -> throw new ApiException(400, "unknown_subcommand", "Use: exceptions list|claim|show|resolve|release");
        }
        return 0;
    }

    private static int audit() throws SQLException {
        boolean ok = true;
        out.println("Ledger audit (rules every correct double-entry ledger must satisfy):");
        try (Connection c = db().connect()) {
            for (LedgerAudit.Check check : LedgerAudit.run(c)) {
                out.printf("  %s  %s%n        %s%n", check.passed() ? "PASS" : "FAIL", check.name(), check.detail());
                ok &= check.passed();
            }
        }
        out.println(ok ? "\nAll checks passed." : "\nSOME CHECKS FAILED.");
        return ok ? 0 : 1;
    }

    private static int serve(Args a) throws Exception {
        int port = a.intFlag("port", config().apiPort());
        new ApiServer(config(), db(), ledger()).start(port);
        out.printf("""
                PayRecon API listening on http://localhost:%d   (Ctrl+C to stop)

                  GET  /v1/health
                  POST /v1/payments                     body {"merchant_id":"M00001","amount_minor":49900,"method":"UPI"}
                  GET  /v1/payments/{id}
                  POST /v1/payments/{id}/refunds        body {"amount_minor":10000}
                  GET  /v1/merchants/{id}/balance       (MySQL ledger)
                  GET  /v1/merchants/{id}/statements    (MongoDB)
                  POST /v1/exceptions/claim             body {"analyst":"alice"}
                  Every POST needs an Idempotency-Key header.

                Try:  curl -s -X POST localhost:%d/v1/payments -H 'Idempotency-Key: demo-1' \\
                        -H 'Content-Type: application/json' -d '{"merchant_id":"M00001","amount_minor":49900}'
                """, port, port);
        Thread.currentThread().join();
        return 0;
    }

    private static int stress(Args a) throws Exception {
        String url = a.flag("url", "http://localhost:" + config().apiPort());
        return new StressTest(db(), ledger(), url).run(out) ? 0 : 1;
    }

    private static int statement(Args a) {
        String merchant = required(a, "merchant");
        int limit = a.intFlag("limit", 3);
        try (MongoStore m = new MongoStore(config())) {
            List<Document> docs = m.statements(merchant, limit);
            if (docs.isEmpty()) {
                out.println("No payout statements for " + merchant + " (run reconcile first; merchant ids look like M00001)");
                return 1;
            }
            for (Document d : docs) {
                Document s = d.get("summary", Document.class);
                out.printf("%nPayout %s  ·  %s  ·  %s  ·  batch %s via %s  ·  bank status: %s%n", d.getString("_id"),
                        d.getString("payout_date"), Money.inr(d.getLong("amount_minor")), d.getString("batch_id"),
                        d.getString("payout_bank"), d.getString("bank_status"));
                out.printf("  %d payments, gross %s, fees %s, refunds %s, net %s%n", s.getLong("payments"),
                        Money.inr(s.getLong("gross_minor")), Money.inr(s.getLong("fees_minor")),
                        Money.inr(s.getLong("refunds_minor")), Money.inr(s.getLong("net_minor")));
                List<Document> payments = d.getList("payments", Document.class);
                for (int i = 0; i < Math.min(3, payments.size()); i++) {
                    Document p = payments.get(i);
                    out.printf("    %s  %s  %-10s gross %s  fee %s%n", p.getString("payment_id"), p.getString("captured_at"),
                            p.getString("method"), Money.inr(p.getLong("gross_minor")), Money.inr(p.getLong("fee_minor")));
                }
                if (payments.size() > 3) {
                    out.printf("    ... and %d more payments%n", payments.size() - 3);
                }
            }
            return 0;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Config config() {
        if (config == null) {
            config = Config.load();
        }
        return config;
    }

    private static Db db() {
        if (db == null) {
            db = new Db(config());
        }
        return db;
    }

    private static LedgerService ledger() {
        if (ledger == null) {
            ledger = new LedgerService();
        }
        return ledger;
    }

    private static ExceptionWorkflow workflow() {
        return new ExceptionWorkflow(db());
    }

    private static String required(Args a, String flag) {
        String v = a.flag(flag, null);
        if (v == null || v.isBlank() || "true".equals(v)) {
            throw new ApiException(400, "missing_flag", "--" + flag + " is required");
        }
        return v;
    }

    private static long idArg(Args a) {
        String v = a.pos(2, null);
        if (v == null) {
            throw new ApiException(400, "missing_id", "Give the exception id, e.g. ./payrecon exceptions show 12");
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new ApiException(400, "bad_id", "Exception id must be a number, got " + v);
        }
    }

    private static String fmt(long n) {
        return String.format("%,d", n);
    }
}
