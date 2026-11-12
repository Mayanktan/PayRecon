package com.payrecon.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrecon.common.Money;
import com.payrecon.db.Db;
import com.payrecon.exceptions.ExceptionWorkflow;
import com.payrecon.ledger.LedgerAudit;
import com.payrecon.ledger.LedgerService;

import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * Proves the API stays correct under concurrency. Every scenario fires many requests at the SAME instant:
 * all threads wait behind a "start gate" (a CountDownLatch) and are released together.
 */
public final class StressTest {

    record Reply(int status, String body, boolean replayed) {
    }

    private final Db db;
    private final LedgerService ledger;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper json = new ObjectMapper();

    public StressTest(Db db, LedgerService ledger, String baseUrl) {
        this.db = db;
        this.ledger = ledger;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public boolean run(PrintStream out) throws Exception {
        db.inTransaction(c -> {
            ledger.ensureSystemAccounts(c);
            for (int i = 1; i <= 5; i++) {
                ledger.ensureMerchant(c, "MTEST" + i, "Stress Test Store " + i, 200, "DAILY", "HDFC");
            }
            return null;
        });
        try {
            send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/health")).GET().build());
        } catch (Exception e) {
            out.println("The API is not reachable at " + baseUrl + ". Start it first in another terminal: ./payrecon serve");
            return false;
        }

        boolean ok = sameKeyBurst(out);
        ok &= refundRace(out);
        ok &= manyPayments(out);
        ok &= queueClaims(out);

        out.println("\nLedger audit after the stress test:");
        boolean auditOk = true;
        try (Connection c = db.connect()) {
            for (LedgerAudit.Check check : LedgerAudit.run(c)) {
                out.printf("  %s  %s  (%s)%n", check.passed() ? "PASS" : "FAIL", check.name(), check.detail());
                auditOk &= check.passed();
            }
        }
        ok &= auditOk;
        out.printf("%nSTRESS TEST RESULT: %s%n", ok ? "PASS" : "FAIL");
        return ok;
    }

    /** 50 identical requests with the SAME Idempotency-Key at once: exactly one payment may be created. */
    private boolean sameKeyBurst(PrintStream out) throws Exception {
        String key = "stress-same-key-" + UUID.randomUUID();
        String body = "{\"merchant_id\":\"MTEST1\",\"amount_minor\":99900,\"method\":\"UPI\"}";
        List<Reply> replies = burst(50, i -> post("/v1/payments", key, body));
        int fresh = 0;
        int replayed = 0;
        Set<String> ids = new HashSet<>();
        for (Reply r : replies) {
            if (r.status() == 201 && !r.replayed()) {
                fresh++;
            }
            if (r.status() == 201 && r.replayed()) {
                replayed++;
            }
            JsonNode node = json.readTree(r.body());
            if (node.hasNonNull("payment_id")) {
                ids.add(node.get("payment_id").asText());
            }
        }
        long rows = ids.size() == 1 ? count("SELECT COUNT(*) FROM payment WHERE payment_id = ?", ids.iterator().next()) : -1;
        boolean pass = fresh == 1 && replayed == 49 && ids.size() == 1 && rows == 1;
        out.printf("%n1) 50 parallel requests, same Idempotency-Key%n");
        out.printf("   created %d, replayed %d, distinct payment ids %d, rows in MySQL %d  ->  %s%n",
                fresh, replayed, ids.size(), rows, pass ? "PASS (charged exactly once)" : "FAIL");
        return pass;
    }

    /** 20 refunds of ₹100 at once on a ₹1,000 payment: exactly 10 may succeed (no over-refund). */
    private boolean refundRace(PrintStream out) throws Exception {
        Reply created = send(post("/v1/payments", "stress-refund-base-" + UUID.randomUUID(),
                "{\"merchant_id\":\"MTEST2\",\"amount_minor\":100000,\"method\":\"UPI\"}"));
        String paymentId = json.readTree(created.body()).get("payment_id").asText();
        String run = UUID.randomUUID().toString();
        List<Reply> replies = burst(20, i -> post("/v1/payments/" + paymentId + "/refunds", "stress-refund-" + run + "-" + i,
                "{\"amount_minor\":10000}"));
        int ok = 0;
        int rejected = 0;
        for (Reply r : replies) {
            if (r.status() == 201) {
                ok++;
            } else if (r.status() == 409) {
                rejected++;
            }
        }
        long refunded = count("SELECT refunded_minor FROM payment WHERE payment_id = ?", paymentId);
        boolean pass = ok == 10 && rejected == 10 && refunded == 100_000;
        out.printf("%n2) 20 parallel refunds of ₹100 on one ₹1,000 payment%n");
        out.printf("   succeeded %d, rejected %d, total refunded %s  ->  %s%n", ok, rejected, Money.inr(refunded),
                pass ? "PASS (never refunded more than paid)" : "FAIL");
        return pass;
    }

    /** 200 different payments at once across 5 merchants: all must succeed and the ledger must stay balanced. */
    private boolean manyPayments(PrintStream out) throws Exception {
        String run = UUID.randomUUID().toString();
        long start = System.nanoTime();
        List<Reply> replies = burst(200, i -> post("/v1/payments", "stress-many-" + run + "-" + i,
                "{\"merchant_id\":\"MTEST" + (1 + i % 5) + "\",\"amount_minor\":" + (10_000 + i * 7)
                        + ",\"method\":\"" + (i % 3 == 0 ? "CARD" : "UPI") + "\"}"));
        double seconds = (System.nanoTime() - start) / 1e9;
        long created = replies.stream().filter(r -> r.status() == 201).count();
        boolean pass = created == 200;
        out.printf("%n3) 200 parallel payments across 5 merchants%n");
        out.printf("   created %d of 200 in %.2f s (%.0f payments/s)  ->  %s%n", created, seconds, created / seconds,
                pass ? "PASS" : "FAIL");
        return pass;
    }

    /** 6 analysts claim work at once: SKIP LOCKED must hand each one a DIFFERENT exception. */
    private boolean queueClaims(PrintStream out) throws Exception {
        ExceptionWorkflow workflow = new ExceptionWorkflow(db);
        int runId;
        try {
            runId = workflow.resolveRunId("latest");
        } catch (Exception e) {
            out.printf("%n4) Parallel queue claims: skipped (no reconciliation run yet)%n");
            return true;
        }
        List<Reply> replies = burst(6, i -> post("/v1/exceptions/claim", null, "{\"analyst\":\"analyst" + (i + 1) + "\"}"));
        List<Long> ids = new ArrayList<>();
        for (Reply r : replies) {
            JsonNode node = json.readTree(r.body());
            if (node.hasNonNull("exception_id")) {
                ids.add(node.get("exception_id").asLong());
            }
        }
        boolean pass = ids.size() == 6 && new HashSet<>(ids).size() == 6;
        out.printf("%n4) 6 analysts claim from the exceptions queue at once (run #%d)%n", runId);
        out.printf("   claimed %s  ->  %s%n", ids, pass ? "PASS (no two analysts got the same item)" : "FAIL");
        for (Long id : ids) {
            workflow.release(id, "stress-test"); // put them back so the queue is unchanged
        }
        return pass;
    }

    // ------------------------------------------------------------------ helpers

    private List<Reply> burst(int n, IntFunction<HttpRequest> requestFor) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<Reply>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                HttpRequest request = requestFor.apply(i);
                futures.add(pool.submit(() -> {
                    gate.await(); // everybody waits here...
                    return send(request);
                }));
            }
            gate.countDown(); // ...and starts at the same moment
            List<Reply> replies = new ArrayList<>();
            for (Future<Reply> f : futures) {
                replies.add(f.get(120, TimeUnit.SECONDS));
            }
            return replies;
        } finally {
            pool.shutdownNow();
        }
    }

    private HttpRequest post(String path, String idempotencyKey, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        return b.build();
    }

    private Reply send(HttpRequest request) throws Exception {
        HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Reply(r.statusCode(), r.body(), r.headers().firstValue("Idempotent-Replayed").isPresent());
    }

    private long count(String sql, String param) throws SQLException {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }
}
