package com.payrecon.recon;

import com.payrecon.common.ApiException;
import com.payrecon.common.FileUtil;
import com.payrecon.common.TablePrinter;
import com.payrecon.common.Tsv;
import com.payrecon.db.Db;
import com.payrecon.recon.match.Rows;
import com.payrecon.recon.match.WindowMatcher;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Proves the engine is correct. The generator planted known problems and wrote them to answer_key.csv;
 * here we check that every planted problem was reported, and that nothing else was reported.
 * (Testing against ground truth: "it ran without crashing" is not the same as "it is right".)
 */
public final class Verifier {

    record Planted(String type, String ref, String bankLineId, LocalDate date) {
    }

    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("MISSING_IN_BANK", "Payment never settled by the bank");
        LABELS.put("AMOUNT_MISMATCH", "Bank settled a different amount");
        LABELS.put("DUPLICATE_IN_BANK", "Bank settled the same payment twice");
        LABELS.put("MISSING_IN_LEDGER", "Money from the bank with no matching payment");
        LABELS.put("MALFORMED_BANK_LINE", "Unreadable bank line");
        LABELS.put("PAYOUT_BATCH_MISMATCH", "Bank debited a wrong payout batch total");
        LABELS.put("PAYOUT_NOT_EXECUTED", "Payout batch never debited");
        LABELS.put("EXPECT_AUTO_MATCH", "Short/missing reference (pass 2 should match)");
    }

    private Verifier() {
    }

    public static boolean verify(Db db, Path dataDir, int runId, PrintStream out) throws Exception {
        LocalDate cutoff;
        try (Connection c = db.connect();
             PreparedStatement ps = c.prepareStatement("SELECT through_date, status FROM recon_run WHERE run_id = ?")) {
            ps.setInt(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(404, "run_not_found", "No reconciliation run #" + runId);
                }
                if (!"COMPLETED".equals(rs.getString(2))) {
                    throw new ApiException(409, "run_not_completed", "Run #" + runId + " is " + rs.getString(2));
                }
                cutoff = rs.getObject(1, LocalDate.class);
            }
        }

        Path keyFile = dataDir.resolve("answer_key.csv");
        if (!Files.exists(keyFile)) {
            throw new ApiException(404, "no_answer_key", "No answer key at " + keyFile + " (it is written by ./payrecon generate)");
        }
        List<Planted> planted = new ArrayList<>();
        List<String> lines = Files.readAllLines(keyFile);
        for (int i = 1; i < lines.size(); i++) {
            String[] f = lines.get(i).split(",", -1);
            if (f.length >= 4) {
                planted.add(new Planted(f[0], blankToNull(f[1]), blankToNull(f[2]), LocalDate.parse(f[3])));
            }
        }

        Map<String, Set<String>> reported = new HashMap<>();
        List<String[]> actionable = new ArrayList<>();
        try (Connection c = db.connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT exception_type, ledger_ref, bank_line_id, actionable FROM recon_exception WHERE run_id = ?")) {
            ps.setInt(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Set<String> keys = reported.computeIfAbsent(rs.getString(1), k -> new HashSet<>());
                    if (rs.getString(2) != null) {
                        keys.add(rs.getString(2));
                    }
                    if (rs.getString(3) != null) {
                        keys.add(rs.getString(3));
                    }
                    if (rs.getBoolean(4)) {
                        actionable.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(3)});
                    }
                }
            }
        }
        Map<String, String> windowMatches = new HashMap<>(); // bank line id -> payment id matched in pass 2
        Path matchesDir = dataDir.resolve("warehouse").resolve("recon_matches").resolve("run_id=" + runId);
        for (Path file : FileUtil.partFiles(matchesDir)) {
            for (String line : Files.readAllLines(file)) {
                String[] f = Tsv.split(line);
                if (f.length > 2 && Rows.PASS_WINDOW.equals(f[0])) {
                    windowMatches.put(f[2], f[1]);
                }
            }
        }
        Set<String> inReview = reported.getOrDefault("PENDING_REVIEW", Set.of());
        Set<String> ambiguous = reported.getOrDefault("AMBIGUOUS_MATCH", Set.of());

        Map<String, int[]> stats = new LinkedHashMap<>(); // planted, found, review, missed
        LABELS.keySet().forEach(t -> stats.put(t, new int[4]));
        List<String> missedExamples = new ArrayList<>();
        Set<String> explained = new HashSet<>();
        int notDueYet = 0;
        for (Planted p : planted) {
            explained.add(p.type() + "|" + p.ref());
            explained.add(p.type() + "|" + p.bankLineId());
            if ("EXPECT_AUTO_MATCH".equals(p.type()) || "MISSING_IN_LEDGER".equals(p.type())) {
                explained.add("AMBIGUOUS_MATCH|" + p.bankLineId());
            }
            if (!isDue(p, cutoff)) {
                notDueYet++;
                continue;
            }
            int[] s = stats.computeIfAbsent(p.type(), t -> new int[4]);
            s[0]++;
            String outcome = switch (p.type()) {
                case "MISSING_IN_BANK" -> has(reported, p.type(), p.ref()) ? "found"
                        : inReview.contains(p.ref()) ? "review" : "missed";
                case "AMOUNT_MISMATCH", "PAYOUT_BATCH_MISMATCH", "PAYOUT_NOT_EXECUTED" ->
                        has(reported, p.type(), p.ref()) ? "found" : "missed";
                case "DUPLICATE_IN_BANK", "MISSING_IN_LEDGER", "MALFORMED_BANK_LINE" ->
                        has(reported, p.type(), p.bankLineId()) ? "found"
                                : ambiguous.contains(p.bankLineId()) ? "review" : "missed";
                case "EXPECT_AUTO_MATCH" -> {
                    String matchedTo = windowMatches.get(p.bankLineId());
                    if (p.ref() != null && p.ref().equals(matchedTo)) {
                        yield "found";
                    }
                    if (matchedTo != null) {
                        yield "wrong";
                    }
                    yield ambiguous.contains(p.bankLineId()) ? "review" : "missed";
                }
                default -> "found";
            };
            switch (outcome) {
                case "found" -> s[1]++;
                case "review" -> s[2]++;
                default -> {
                    s[3]++;
                    if (missedExamples.size() < 5) {
                        missedExamples.add(outcome + ": " + p.type() + " " + (p.ref() != null ? p.ref() : p.bankLineId()));
                    }
                }
            }
        }

        int unexpected = 0;
        List<String> unexpectedExamples = new ArrayList<>();
        for (String[] a : actionable) {
            boolean ok = explained.contains(a[0] + "|" + a[1]) || explained.contains(a[0] + "|" + a[2]);
            if (!ok) {
                unexpected++;
                if (unexpectedExamples.size() < 5) {
                    unexpectedExamples.add(a[0] + " " + (a[1] != null ? a[1] : a[2]));
                }
            }
        }

        out.printf("%nVerifying run #%d (cutoff %s) against data/answer_key.csv%n%n", runId, cutoff);
        TablePrinter table = new TablePrinter("Planted problem", "Due by cutoff", "Reported", "Sent to review", "Missed")
                .alignRight(1, 2, 3, 4);
        int missedTotal = 0;
        for (Map.Entry<String, int[]> e : stats.entrySet()) {
            int[] s = e.getValue();
            table.add(LABELS.getOrDefault(e.getKey(), e.getKey()), s[0], s[1], s[2], s[3]);
            missedTotal += s[3];
        }
        table.print(out);
        out.printf("%nNot due yet at this cutoff (planted after it): %,d%n", notDueYet);
        out.printf("Unexpected actionable exceptions (reported but never planted): %,d%n", unexpected);
        unexpectedExamples.forEach(x -> out.println("   e.g. " + x));
        missedExamples.forEach(x -> out.println("   missed -> " + x));
        out.println("\"Sent to review\" = several payments fit a bank line equally well, so the engine refused to");
        out.println("guess and queued it for a person (AMBIGUOUS_MATCH / PENDING_REVIEW). That is the safe outcome.");
        boolean pass = missedTotal == 0 && unexpected == 0;
        out.printf("%nRESULT: %s%n", pass ? "PASS - every planted problem was caught, and nothing extra was reported"
                : "FAIL - see the missed / unexpected lines above");
        return pass;
    }

    static boolean isDue(Planted p, LocalDate cutoff) {
        return switch (p.type()) {
            case "MISSING_IN_BANK" -> ChronoUnit.DAYS.between(p.date(), cutoff)
                    > WindowMatcher.expectedLagDays("CARDNET") + WindowMatcher.GRACE_DAYS;
            case "PAYOUT_NOT_EXECUTED" -> p.date().isBefore(cutoff);
            default -> !p.date().isAfter(cutoff);
        };
    }

    private static boolean has(Map<String, Set<String>> reported, String type, String key) {
        return key != null && reported.getOrDefault(type, Set.of()).contains(key);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
