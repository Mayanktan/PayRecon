package com.payrecon.recon.match;

import com.payrecon.common.Tsv;

import java.time.LocalDate;

/** The two output row formats of the matching engine (tab-separated, read by MySQL loading and by Hive). */
public final class Rows {

    public static final String PASS_EXACT = "EXACT_REF";
    public static final String PASS_WINDOW = "AMOUNT_DATE_WINDOW";
    public static final String PASS_PAYOUT = "PAYOUT_BATCH";

    private Rows() {
    }

    /** match_pass, ledger_ref, bank_line_id, bank, merchant_id, ledger_amount, bank_amount, ledger_date, bank_date */
    public static String matched(String pass, String ledgerRef, String merchantId, long ledgerAmount,
                                 LocalDate ledgerDate, BankItem b) {
        return Tsv.join(pass, ledgerRef, b.lineId(), b.bank(), merchantId, ledgerAmount, b.amount(), ledgerDate,
                b.date());
    }

    /**
     * exception_type, actionable, ledger_ref, bank_line_id, bank, merchant_id, ledger_amount, bank_amount,
     * diff, ledger_date, bank_date, note
     */
    public static String exception(ExceptionType type, String ledgerRef, String bankLineId, String bank,
                                   String merchantId, Long ledgerAmount, Long bankAmount, Long diff,
                                   LocalDate ledgerDate, LocalDate bankDate, String note) {
        return Tsv.join(type.name(), type.actionable, ledgerRef, bankLineId, bank, merchantId, ledgerAmount,
                bankAmount, diff, ledgerDate, bankDate, note);
    }

    public static String ledgerOnly(ExceptionType type, LedgerItem l, String note) {
        return exception(type, l.paymentId(), null, l.bank(), l.merchantId(), l.amount(), null, null, l.date(),
                null, note);
    }

    public static String bankOnly(ExceptionType type, BankItem b, String ledgerRef, String note) {
        return exception(type, ledgerRef, b.lineId(), b.bank(), null, null, b.amount(), null, null, b.date(), note);
    }
}
