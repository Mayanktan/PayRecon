package com.payrecon.recon.mr;

import com.payrecon.common.Tsv;

/**
 * Running total of ledger payouts for one batch. Used by BOTH the combiner (map side) and the reducer,
 * which is why the combiner's output format ("P total count date bank") equals the mapper's input to it.
 */
final class PayoutTotals {

    long total;
    long count;
    String earliestDate;
    String bank;
    boolean any;

    void add(String record) {
        String[] f = Tsv.split(record); // P, amount, count, payout_date, bank
        total += Long.parseLong(f[1]);
        count += Long.parseLong(f[2]);
        if (earliestDate == null || f[3].compareTo(earliestDate) < 0) {
            earliestDate = f[3];
        }
        bank = f[4];
        any = true;
    }

    String toRecord() {
        return Tsv.join("P", total, count, earliestDate, bank);
    }
}
