package com.payrecon.exceptions;

import com.payrecon.common.ApiException;
import com.payrecon.common.Money;
import com.payrecon.common.TablePrinter;
import com.payrecon.db.Db;
import com.payrecon.mongo.MongoStore;
import org.bson.Document;
import org.bson.json.JsonWriterSettings;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The analysts' work queue for exceptions, stored in MySQL because it needs transactions and an audit trail.
 *  claim   = SELECT ... FOR UPDATE SKIP LOCKED: many analysts can pull work at once and never get the same item
 *  resolve = optimistic locking with a version column: if someone else changed the row meanwhile, we refuse
 * Every change is written to exception_event (who did what, when).
 */
public final class ExceptionWorkflow {

    public static final List<String> RESOLUTIONS = List.of("WRITE_OFF", "BANK_CORRECTED", "LEDGER_CORRECTED", "NO_ACTION");

    private final Db db;

    public ExceptionWorkflow(Db db) {
        this.db = db;
    }

    public int resolveRunId(String arg) throws SQLException {
        if (arg == null || "latest".equalsIgnoreCase(arg)) {
            try (Connection c = db.connect(); Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT MAX(run_id) FROM recon_run WHERE status = 'COMPLETED'")) {
                rs.next();
                int id = rs.getInt(1);
                if (rs.wasNull()) {
                    throw new ApiException(404, "no_runs", "No completed reconciliation runs yet. Run ./payrecon reconcile first.");
                }
                return id;
            }
        }
        try {
            return Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            throw new ApiException(400, "bad_run", "--run must be a number or 'latest'");
        }
    }

    public void list(int runId, String status, String type, boolean includeInformational, int limit, PrintStream out)
            throws SQLException {
        try (Connection c = db.connect()) {
            out.printf("%nRun #%d exceptions by type and status%n", runId);
            TablePrinter summary = new TablePrinter("Type", "Open", "Investigating", "Resolved").alignRight(1, 2, 3);
            Map<String, int[]> counts = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT exception_type, status, COUNT(*) FROM recon_exception WHERE run_id = ?"
                            + " GROUP BY exception_type, status ORDER BY exception_type")) {
                ps.setInt(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int[] row = counts.computeIfAbsent(rs.getString(1), k -> new int[3]);
                        int idx = switch (rs.getString(2)) {
                            case "OPEN" -> 0;
                            case "INVESTIGATING" -> 1;
                            default -> 2;
                        };
                        row[idx] = rs.getInt(3);
                    }
                }
            }
            counts.forEach((t, r) -> summary.add(t, r[0], r[1], r[2]));
            summary.print(out);

            StringBuilder sql = new StringBuilder("SELECT exception_id, exception_type, status, bank,"
                    + " COALESCE(ledger_ref, bank_line_id), COALESCE(diff_minor, ledger_amount_minor, bank_amount_minor),"
                    + " assigned_to, note FROM recon_exception WHERE run_id = ?");
            List<Object> params = new ArrayList<>();
            params.add(runId);
            if (!includeInformational) {
                sql.append(" AND actionable = TRUE");
            }
            if (status != null) {
                sql.append(" AND status = ?");
                params.add(status.toUpperCase());
            }
            if (type != null) {
                sql.append(" AND exception_type = ?");
                params.add(type.toUpperCase());
            }
            sql.append(" ORDER BY exception_id LIMIT ?");
            params.add(limit);
            TablePrinter table = new TablePrinter("Id", "Type", "Status", "Bank", "Reference / bank line", "Amount",
                    "Assigned", "Note").alignRight(0, 5);
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long amount = rs.getLong(6);
                        String note = rs.getString(8);
                        table.add(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                                rs.wasNull() ? "" : Money.inr(amount), rs.getString(7),
                                note != null && note.length() > 60 ? note.substring(0, 57) + "..." : note);
                    }
                }
            }
            out.printf("%nQueue (highest priority first%s), showing up to %d:%n",
                    includeInformational ? ", including informational" : ", actionable only", limit);
            table.print(out);
        }
    }

    /** Takes the next OPEN actionable exception. Returns null if the queue is empty. */
    public Long claim(int runId, String analyst) throws SQLException {
        return db.inTransaction(c -> {
            Long id = null;
            // ORDER BY exception_id follows the index (run_id, status, actionable, [primary key]), so MySQL reads
            // rows in order and locks only the first one that is not already locked. With an ORDER BY that needs a
            // sort, MySQL would read (and lock) every matching row first, and SKIP LOCKED would starve other analysts.
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT exception_id FROM recon_exception WHERE run_id = ? AND status = 'OPEN' AND actionable = TRUE"
                            + " ORDER BY exception_id LIMIT 1 FOR UPDATE SKIP LOCKED")) {
                ps.setInt(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        id = rs.getLong(1);
                    }
                }
            }
            if (id == null) {
                return null;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE recon_exception SET status = 'INVESTIGATING', assigned_to = ?, version = version + 1"
                            + " WHERE exception_id = ?")) {
                ps.setString(1, analyst);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            event(c, id, analyst, "CLAIMED", null);
            return id;
        });
    }

    public void resolve(long id, String analyst, String resolution, String note) throws SQLException {
        String res = resolution == null ? null : resolution.toUpperCase();
        if (!RESOLUTIONS.contains(res)) {
            throw new ApiException(400, "bad_resolution", "--resolution must be one of " + RESOLUTIONS);
        }
        db.inTransaction(c -> {
            String status;
            String assigned;
            int version;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT status, assigned_to, version FROM recon_exception WHERE exception_id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new ApiException(404, "not_found", "No exception #" + id);
                    }
                    status = rs.getString(1);
                    assigned = rs.getString(2);
                    version = rs.getInt(3);
                }
            }
            if ("RESOLVED".equals(status)) {
                throw new ApiException(409, "already_resolved", "Exception #" + id + " is already resolved");
            }
            if (!"INVESTIGATING".equals(status) || !analyst.equals(assigned)) {
                throw new ApiException(409, "not_yours",
                        "Claim it first (./payrecon exceptions claim --analyst " + analyst + "); it is " + status
                                + (assigned != null ? " by " + assigned : ""));
            }
            int updated;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE recon_exception SET status = 'RESOLVED', resolution = ?, resolution_note = ?,"
                            + " version = version + 1 WHERE exception_id = ? AND version = ?")) {
                ps.setString(1, res);
                ps.setString(2, note);
                ps.setLong(3, id);
                ps.setInt(4, version);
                updated = ps.executeUpdate();
            }
            if (updated == 0) { // someone changed the row after we read it: optimistic lock failed
                throw new ApiException(409, "concurrent_change", "Exception #" + id + " was changed by someone else; try again");
            }
            event(c, id, analyst, "RESOLVED", res + (note == null ? "" : ": " + note));
            return null;
        });
    }

    public void release(long id, String actor) throws SQLException {
        db.inTransaction(c -> {
            int updated;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE recon_exception SET status = 'OPEN', assigned_to = NULL, version = version + 1"
                            + " WHERE exception_id = ? AND status = 'INVESTIGATING'")) {
                ps.setLong(1, id);
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                throw new ApiException(409, "not_investigating", "Exception #" + id + " is not being investigated");
            }
            event(c, id, actor, "RELEASED", null);
            return null;
        });
    }

    public Map<String, Object> details(long id) throws SQLException {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT exception_id, run_id, exception_type, actionable, status, assigned_to, bank, ledger_ref, bank_line_id,"
                        + " merchant_id, ledger_amount_minor, bank_amount_minor, diff_minor, ledger_date, bank_date, note,"
                        + " resolution, resolution_note, version FROM recon_exception WHERE exception_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(404, "not_found", "No exception #" + id);
                }
                Map<String, Object> m = new LinkedHashMap<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    m.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                }
                return m;
            }
        }
    }

    public void show(long id, MongoStore mongo, PrintStream out) throws SQLException {
        Map<String, Object> d = details(id);
        out.printf("%nException #%d%n", id);
        d.forEach((k, v) -> {
            if (v != null) {
                String text = (k.endsWith("_minor") && v instanceof Number n) ? Money.inr(n.longValue()) + "  (" + v + " paise)" : v.toString();
                out.printf("  %-20s %s%n", k, text);
            }
        });
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT created_at, actor, action, detail FROM exception_event WHERE exception_id = ? ORDER BY event_id")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                out.println("\nHistory (exception_event audit trail):");
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    out.printf("  %s  %-12s %-10s %s%n", rs.getTimestamp(1), rs.getString(2), rs.getString(3),
                            rs.getString(4) == null ? "" : rs.getString(4));
                }
                if (!any) {
                    out.println("  (no events yet)");
                }
            }
        }
        Object lineId = d.get("bank_line_id");
        if (lineId != null && mongo != null) {
            out.println("\nOriginal bank record from MongoDB (exactly as the bank sent it):");
            try {
                Document raw = mongo.rawRecord(lineId.toString());
                out.println(raw == null ? "  (not found - run reconcile without --skip-mongo)"
                        : "  " + raw.toJson(JsonWriterSettings.builder().indent(true).build()).replace("\n", "\n  "));
            } catch (RuntimeException e) {
                out.println("  (MongoDB not reachable: " + e.getMessage() + ")");
            }
        }
    }

    private static void event(Connection c, long id, String actor, String action, String detail) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO exception_event (exception_id, actor, action, detail) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, id);
            ps.setString(2, actor);
            ps.setString(3, action);
            ps.setString(4, detail == null || detail.length() <= 255 ? detail : detail.substring(0, 255));
            ps.executeUpdate();
        }
    }
}
