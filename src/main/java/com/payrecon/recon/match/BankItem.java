package com.payrecon.recon.match;

import com.payrecon.common.Tsv;

import java.time.LocalDate;
import java.util.Comparator;

/** One normalised bank line (credit = money in, debit = money out). */
public record BankItem(String lineId, String bank, String direction, String ref, String refQuality, long amount,
                       String bankTxnId, LocalDate date) {

    /**
     * Physical order: by value date, then file name, then line NUMBER. Comparing the line id as plain text
     * would put line 10 before line 9, and then we would call the wrong copy of a duplicate "the original".
     */
    public static final Comparator<BankItem> BY_DATE = Comparator.comparing(BankItem::date)
            .thenComparing(BankItem::fileName)
            .thenComparingLong(BankItem::lineNumber);

    /** line_id, bank, direction, ref, ref_quality, amount, bank_txn_id, value_date */
    public String toTsv() {
        return Tsv.join(lineId, bank, direction, ref, refQuality, amount, bankTxnId, date);
    }

    public static BankItem fromTsv(String[] f, int offset) {
        return new BankItem(f[offset], f[offset + 1], f[offset + 2], Tsv.str(f[offset + 3]), f[offset + 4],
                Long.parseLong(f[offset + 5]), Tsv.str(f[offset + 6]), LocalDate.parse(f[offset + 7]));
    }

    public boolean isCredit() {
        return "CREDIT".equals(direction);
    }

    public String fileName() {
        int first = lineId.indexOf(':');
        int last = lineId.lastIndexOf(':');
        return first >= 0 && last > first ? lineId.substring(first + 1, last) : lineId;
    }

    public long lineNumber() {
        int last = lineId.lastIndexOf(':');
        try {
            return Long.parseLong(lineId.substring(last + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
