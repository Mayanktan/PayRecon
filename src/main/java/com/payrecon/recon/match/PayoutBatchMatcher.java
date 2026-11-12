package com.payrecon.recon.match;

import com.payrecon.common.Money;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Pass 3: payouts. We send many merchant payouts as one batch, and the bank shows ONE debit for the
 * whole batch (a many-to-one match). We compare the batch total from the ledger with the bank debit.
 */
public final class PayoutBatchMatcher {

    public record Result(List<String> matched, List<String> exceptions) {
    }

    private PayoutBatchMatcher() {
    }

    /**
     * @param ledgerTotal null when no ledger batch has this reference (an unknown bank debit)
     */
    public static Result match(String batchRef, Long ledgerTotal, int payoutCount, LocalDate payoutDate,
                               String ledgerBank, List<BankItem> bankLines, LocalDate cutoff) {
        List<String> matched = new ArrayList<>();
        List<String> exceptions = new ArrayList<>();
        List<BankItem> bank = new ArrayList<>(bankLines);
        bank.sort(BankItem.BY_DATE);

        if (ledgerTotal == null) {
            for (BankItem b : bank) {
                exceptions.add(Rows.bankOnly(ExceptionType.UNKNOWN_DEBIT, b, b.ref(), "debit matches no payout batch"));
            }
            return new Result(matched, exceptions);
        }
        if (bank.isEmpty()) {
            // The bank debits a batch on its payout date. Missing for a full day means it never happened.
            ExceptionType type = payoutDate.isBefore(cutoff) ? ExceptionType.PAYOUT_NOT_EXECUTED : ExceptionType.PAYOUT_PENDING;
            exceptions.add(Rows.exception(type, batchRef, null, ledgerBank, null, ledgerTotal, null, null, payoutDate,
                    null, payoutCount + " payouts in this batch"));
            return new Result(matched, exceptions);
        }

        BankItem first = bank.get(0);
        matched.add(Rows.matched(Rows.PASS_PAYOUT, batchRef, null, ledgerTotal, payoutDate, first));
        if (first.amount() != ledgerTotal) {
            exceptions.add(Rows.exception(ExceptionType.PAYOUT_BATCH_MISMATCH, batchRef, first.lineId(), first.bank(),
                    null, ledgerTotal, first.amount(), ledgerTotal - first.amount(), payoutDate, first.date(),
                    "bank debited " + Money.rupees(first.amount()) + " for " + payoutCount
                            + " payouts totalling " + Money.rupees(ledgerTotal)));
        }
        for (int i = 1; i < bank.size(); i++) {
            BankItem dup = bank.get(i);
            exceptions.add(Rows.exception(ExceptionType.DUPLICATE_IN_BANK, batchRef, dup.lineId(), dup.bank(), null,
                    null, dup.amount(), null, payoutDate, dup.date(), "batch already debited by " + first.lineId()));
        }
        return new Result(matched, exceptions);
    }
}
