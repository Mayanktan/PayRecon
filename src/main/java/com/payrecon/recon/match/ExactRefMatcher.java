package com.payrecon.recon.match;

import com.payrecon.common.Money;

import java.util.ArrayList;
import java.util.List;

/**
 * Pass 1: match by exact payment reference. Called once per reference by the MapReduce reducer,
 * with the ledger payment (0 or 1, because payment ids are unique) and every bank line carrying that reference.
 * Pure function, no Hadoop types, so it is easy to unit-test.
 */
public final class ExactRefMatcher {

    public record Result(List<String> matched, List<String> exceptions, List<LedgerItem> unmatchedLedger) {
    }

    private ExactRefMatcher() {
    }

    public static Result match(List<LedgerItem> ledger, List<BankItem> bankLines) {
        List<String> matched = new ArrayList<>();
        List<String> exceptions = new ArrayList<>();
        List<LedgerItem> unmatched = new ArrayList<>();
        List<BankItem> bank = new ArrayList<>(bankLines);
        bank.sort(BankItem.BY_DATE);

        if (ledger.isEmpty()) {
            for (BankItem b : bank) {
                exceptions.add(Rows.bankOnly(ExceptionType.MISSING_IN_LEDGER, b, null,
                        "reference " + b.ref() + " is not in the ledger"));
            }
            return new Result(matched, exceptions, unmatched);
        }

        LedgerItem l = ledger.get(0);
        if (bank.isEmpty()) {
            unmatched.add(l); // try again in pass 2
            return new Result(matched, exceptions, unmatched);
        }

        BankItem first = bank.get(0);
        matched.add(Rows.matched(Rows.PASS_EXACT, l.paymentId(), l.merchantId(), l.amount(), l.date(), first));
        if (first.amount() != l.amount()) {
            exceptions.add(Rows.exception(ExceptionType.AMOUNT_MISMATCH, l.paymentId(), first.lineId(), first.bank(),
                    l.merchantId(), l.amount(), first.amount(), l.amount() - first.amount(), l.date(), first.date(),
                    "bank paid " + Money.rupees(first.amount()) + ", ledger expected " + Money.rupees(l.amount())));
        }
        for (int i = 1; i < bank.size(); i++) {
            BankItem dup = bank.get(i);
            exceptions.add(Rows.exception(ExceptionType.DUPLICATE_IN_BANK, l.paymentId(), dup.lineId(), dup.bank(),
                    l.merchantId(), null, dup.amount(), null, l.date(), dup.date(),
                    "same reference already settled by " + first.lineId()));
        }
        return new Result(matched, exceptions, unmatched);
    }
}
