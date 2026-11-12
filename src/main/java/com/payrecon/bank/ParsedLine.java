package com.payrecon.bank;

import com.payrecon.common.Tsv;

import java.time.LocalDate;
import java.util.Map;

/**
 * One line of a bank file after parsing.
 * fields     = the bank's own field names and values, kept exactly (stored in MongoDB for audits)
 * the rest   = the normalised view every bank shares (used for matching)
 * refQuality = FULL (complete payment reference), PARTIAL (bank cut it short) or NONE (no reference)
 */
public record ParsedLine(Status status, String lineId, Bank bank, String direction, String ref, String refQuality,
                         long amountMinor, String bankTxnId, LocalDate valueDate, Map<String, Object> fields,
                         String error) {

    public enum Status { OK, SKIP, MALFORMED }

    public static ParsedLine skip(String lineId, Bank bank) {
        return new ParsedLine(Status.SKIP, lineId, bank, null, null, null, 0, null, null, Map.of(), null);
    }

    public static ParsedLine malformed(String lineId, Bank bank, String error) {
        return new ParsedLine(Status.MALFORMED, lineId, bank, null, null, null, 0, null, null, Map.of(), error);
    }

    public boolean isCredit() {
        return "CREDIT".equals(direction);
    }

    /** line_id, bank, direction, ref, ref_quality, amount, bank_txn_id, value_date (same layout as BankItem). */
    public String toTsv() {
        return Tsv.join(lineId, bank, direction, ref, refQuality, amountMinor, bankTxnId, valueDate);
    }
}
