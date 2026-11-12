package com.payrecon.recon.match;

import com.payrecon.common.Tsv;

import java.time.LocalDate;
import java.util.Comparator;

/** A captured payment as the matching engine sees it: what the ledger expects a bank to pay us. */
public record LedgerItem(String paymentId, String merchantId, String bank, long amount, LocalDate date) {

    public static final Comparator<LedgerItem> BY_DATE =
            Comparator.comparing(LedgerItem::date).thenComparing(LedgerItem::paymentId);

    /** payment_id, merchant_id, acquirer, amount, capture_date */
    public String toTsv() {
        return Tsv.join(paymentId, merchantId, bank, amount, date);
    }

    public static LedgerItem fromTsv(String[] f, int offset) {
        return new LedgerItem(f[offset], Tsv.str(f[offset + 1]), f[offset + 2], Long.parseLong(f[offset + 3]),
                LocalDate.parse(f[offset + 4]));
    }
}
