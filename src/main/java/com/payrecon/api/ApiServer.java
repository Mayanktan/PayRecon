package com.payrecon.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrecon.common.ApiException;
import com.payrecon.common.Money;
import com.payrecon.config.Config;
import com.payrecon.db.Db;
import com.payrecon.exceptions.ExceptionWorkflow;
import com.payrecon.ledger.Accounts;
import com.payrecon.ledger.LedgerService;
import com.payrecon.mongo.MongoStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bson.Document;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small REST API on the JDK's built-in HTTP server (no framework).
 *
 * Idempotency: every POST must carry an Idempotency-Key header. The key is stored in MySQL in the SAME
 * transaction as the payment itself, so either both are saved or neither is. If a client retries
 * (for example after a timeout), the stored response is replayed instead of charging twice.
 * If two requests with the same key arrive at the same moment, the second one waits on the row lock
 * of the first, then sees "duplicate key" and replays the first one's answer.
 */
public final class ApiServer {

    public record Response(int status, String body, boolean replayed) {
    }

    private static final Pattern PAYMENT = Pattern.compile("^/v1/payments/([A-Za-z0-9_]+)$");
    private static final Pattern REFUNDS = Pattern.compile("^/v1/payments/([A-Za-z0-9_]+)/refunds$");
    private static final Pattern BALANCE = Pattern.compile("^/v1/merchants/([A-Za-z0-9_]+)/balance$");
    private static final Pattern STATEMENTS = Pattern.compile("^/v1/merchants/([A-Za-z0-9_]+)/statements$");

    private final Config config;
    private final Db db;
    private final LedgerService ledger;
    private final ExceptionWorkflow workflow;
    private final ObjectMapper json = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    private volatile MongoStore mongo;

    public ApiServer(Config config, Db db, LedgerService ledger) {
        this.config = config;
        this.db = db;
        this.ledger = ledger;
        this.workflow = new ExceptionWorkflow(db);
    }

    public HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 512);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(32));
        server.start();
        return server;
    }

    private void handle(HttpExchange ex) throws IOException {
        Response r;
        try {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            r = route(ex.getRequestMethod(), ex.getRequestURI().getPath(), body,
                    ex.getRequestHeaders().getFirst("Idempotency-Key"));
        } catch (ApiException e) {
            r = error(e.status(), e.code(), e.getMessage());
        } catch (Exception e) {
            r = error(500, "internal_error", String.valueOf(e.getMessage()));
        }
        byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        if (r.replayed()) {
            ex.getResponseHeaders().set("Idempotent-Replayed", "true");
        }
        ex.sendResponseHeaders(r.status(), bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    Response route(String method, String path, String body, String idemKey) throws Exception {
        Matcher m;
        if ("GET".equals(method) && "/v1/health".equals(path)) {
            return ok(200, Map.of("status", "ok"));
        }
        if ("POST".equals(method) && "/v1/payments".equals(path)) {
            return idempotent(method, path, body, idemKey, c -> createPayment(c, body));
        }
        if ("GET".equals(method) && (m = PAYMENT.matcher(path)).matches()) {
            return getPayment(m.group(1));
        }
        if ("POST".equals(method) && (m = REFUNDS.matcher(path)).matches()) {
            String paymentId = m.group(1);
            return idempotent(method, path, body, idemKey, c -> createRefund(c, paymentId, body));
        }
        if ("GET".equals(method) && (m = BALANCE.matcher(path)).matches()) {
            return balance(m.group(1));
        }
        if ("GET".equals(method) && (m = STATEMENTS.matcher(path)).matches()) {
            return statements(m.group(1));
        }
        if ("POST".equals(method) && "/v1/exceptions/claim".equals(path)) {
            return claim(body);
        }
        throw new ApiException(404, "not_found", "No route for " + method + " " + path);
    }

    // ------------------------------------------------------------------ idempotency

    private Response idempotent(String method, String path, String body, String key, Db.Work<Response> operation)
            throws SQLException {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw new ApiException(400, "idempotency_key_required",
                    "Send an Idempotency-Key header (1-100 characters), and reuse the same key when you retry");
        }
        String hash = sha256(method + " " + path + "\n" + body);
        try {
            return db.inTransaction(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO idempotency_key (idem_key, request_hash, response_code, response_body) VALUES (?, ?, 0, '')")) {
                    ps.setString(1, key);
                    ps.setString(2, hash);
                    ps.executeUpdate(); // a concurrent request with the same key WAITS here until we finish
                }
                Response r = operation.run(c);
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE idempotency_key SET response_code = ?, response_body = ? WHERE idem_key = ?")) {
                    ps.setInt(1, r.status());
                    ps.setString(2, r.body());
                    ps.setString(3, key);
                    ps.executeUpdate();
                }
                return r;
            });
        } catch (SQLException e) {
            boolean sameKey = Db.isDuplicateKey(e) && e.getMessage() != null
                    && e.getMessage().contains("idempotency_key.PRIMARY");
            if (!sameKey) {
                throw e;
            }
            return replay(key, hash);
        }
    }

    private Response replay(String key, String hash) throws SQLException {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT request_hash, response_code, response_body FROM idempotency_key WHERE idem_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(409, "retry_later", "A request with this key is still being processed; retry");
                }
                if (!hash.equals(rs.getString(1))) {
                    throw new ApiException(422, "idempotency_key_reused",
                            "This Idempotency-Key was already used for a different request");
                }
                return new Response(rs.getInt(2), rs.getString(3), true);
            }
        }
    }

    // ------------------------------------------------------------------ handlers

    private Response createPayment(Connection c, String body) throws SQLException {
        JsonNode req = parse(body);
        String merchantId = text(req, "merchant_id");
        long amount = number(req, "amount_minor");
        String method = req.hasNonNull("method") ? req.get("method").asText() : "UPI";
        String acquirer = req.hasNonNull("acquirer") ? req.get("acquirer").asText()
                : ("CARD".equals(method) ? "CARDNET" : "HDFC");
        String paymentId = "pay_api_" + hex(6);
        LedgerService.PaymentRecord p = ledger.capturePayment(c, paymentId, merchantId, amount, method, acquirer,
                LocalDateTime.now());
        return ok(201, p.toMap());
    }

    private Response createRefund(Connection c, String paymentId, String body) throws SQLException {
        JsonNode req = parse(body);
        long amount = number(req, "amount_minor");
        LedgerService.RefundRecord r = ledger.refund(c, "rfd_" + hex(8), paymentId, amount, LocalDateTime.now());
        return ok(201, r.toMap());
    }

    private Response getPayment(String paymentId) throws SQLException {
        try (Connection c = db.connect()) {
            LedgerService.PaymentRecord p = ledger.findPayment(c, paymentId);
            if (p == null) {
                throw new ApiException(404, "payment_not_found", "No payment " + paymentId);
            }
            return ok(200, p.toMap());
        }
    }

    private Response balance(String merchantId) throws SQLException {
        try (Connection c = db.connect()) {
            long payable = ledger.accountBalance(c, Accounts.merchantPayable(merchantId));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("merchant_id", merchantId);
            m.put("payable_minor", payable);
            m.put("payable_display", Money.inr(payable));
            m.put("meaning", "money PayRecon currently owes this merchant (from the ledger)");
            return ok(200, m);
        }
    }

    private Response statements(String merchantId) {
        List<Document> docs = mongo().statements(merchantId, 10);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < docs.size(); i++) {
            sb.append(i == 0 ? "" : ",").append(docs.get(i).toJson());
        }
        return new Response(200, sb.append("]").toString(), false);
    }

    private Response claim(String body) throws SQLException {
        JsonNode req = parse(body);
        String analyst = text(req, "analyst");
        int runId = workflow.resolveRunId("latest");
        Long id = workflow.claim(runId, analyst);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", runId);
        m.put("exception_id", id);
        m.put("message", id == null ? "queue is empty" : "claimed by " + analyst);
        return ok(200, m);
    }

    // ------------------------------------------------------------------ helpers

    private MongoStore mongo() {
        if (mongo == null) {
            synchronized (this) {
                if (mongo == null) {
                    mongo = new MongoStore(config);
                }
            }
        }
        return mongo;
    }

    private JsonNode parse(String body) {
        try {
            JsonNode node = json.readTree(body == null || body.isBlank() ? "{}" : body);
            if (node == null || !node.isObject()) {
                throw new ApiException(400, "invalid_json", "Request body must be a JSON object");
            }
            return node;
        } catch (IOException e) {
            throw new ApiException(400, "invalid_json", "Request body is not valid JSON");
        }
    }

    private static String text(JsonNode node, String field) {
        if (!node.hasNonNull(field) || node.get(field).asText().isBlank()) {
            throw new ApiException(400, "missing_field", field + " is required");
        }
        return node.get(field).asText();
    }

    private static long number(JsonNode node, String field) {
        if (!node.hasNonNull(field) || !node.get(field).canConvertToLong()) {
            throw new ApiException(400, "missing_field", field + " is required and must be a whole number of paise");
        }
        return node.get(field).asLong();
    }

    private Response ok(int status, Object body) {
        try {
            return new Response(status, json.writeValueAsString(body), false);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Response error(int status, String code, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        return ok(status, Map.of("error", err));
    }

    private String hex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
