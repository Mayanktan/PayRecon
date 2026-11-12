package com.payrecon.recon.match;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Pass 2: match what pass 1 could not, using amount + date window. MapReduce groups records by
 * (bank, exact amount) and SORTS them by date (secondary sort), so this method sees two date-ordered lists.
 *
 * For each bank line, candidates are unmatched payments of the same bank and amount captured between
 * (bank date - expected lag - 4 days) and the bank date. If the bank line kept part of the reference,
 * the payment id must also start with it.
 *   exactly 1 candidate  -> matched
 *   0 candidates         -> MISSING_IN_LEDGER
 *   2+ candidates        -> AMBIGUOUS_MATCH (never guess with money; a person decides)
 * Payments left over are IN_TRANSIT if still young, MISSING_IN_BANK if too old,
 * or PENDING_REVIEW if they were a candidate of an ambiguous line.
 *
 * A two-pointer sliding window keeps this O(n + m) instead of comparing every pair.
 */
public final class WindowMatcher {

    /** Settlements may arrive up to 4 days later than normal. */
    public static final int LATE_ALLOWANCE_DAYS = 4;
    /** An unsettled payment is "in transit" if younger than its normal lag + 2 days at the cutoff. */
    public static final int GRACE_DAYS = 2;

    public record Result(List<String> matched, List<String> exceptions) {
    }

    private WindowMatcher() {
    }

    /** Normal settlement time: banks settle the next day, the card network after two days. */
    public static int expectedLagDays(String bank) {
        return "CARDNET".equals(bank) ? 2 : 1;
    }

    public static Result match(List<LedgerItem> ledgerIn, List<BankItem> bankIn, LocalDate cutoff) {
        List<LedgerItem> ledger = new ArrayList<>(ledgerIn);
        ledger.sort(LedgerItem.BY_DATE);
        List<BankItem> bank = new ArrayList<>(bankIn);
        bank.sort(BankItem.BY_DATE);

        int n = ledger.size();
        boolean[] matched = new boolean[n];
        String[] ambiguousWith = new String[n];
        List<String> matchedRows = new ArrayList<>();
        List<String> exceptions = new ArrayList<>();

        int start = 0; // left edge of the sliding window over the date-sorted ledger list
        for (BankItem b : bank) {
            LocalDate earliest = b.date().minusDays(expectedLagDays(b.bank()) + LATE_ALLOWANCE_DAYS);
            while (start < n && ledger.get(start).date().isBefore(earliest)) {
                start++;
            }
            List<Integer> candidates = new ArrayList<>();
            for (int i = start; i < n && !ledger.get(i).date().isAfter(b.date()); i++) {
                if (matched[i]) {
                    continue;
                }
                boolean partialRef = "PARTIAL".equals(b.refQuality()) && b.ref() != null;
                if (partialRef && !ledger.get(i).paymentId().startsWith(b.ref())) {
                    continue;
                }
                candidates.add(i);
            }

            if (candidates.size() == 1) {
                int i = candidates.get(0);
                matched[i] = true;
                LedgerItem l = ledger.get(i);
                matchedRows.add(Rows.matched(Rows.PASS_WINDOW, l.paymentId(), l.merchantId(), l.amount(), l.date(), b));
            } else if (candidates.isEmpty()) {
                String note = b.ref() == null
                        ? "no reference, and no unsettled payment has this amount and date"
                        : "partial reference " + b.ref() + " matches no unsettled payment";
                exceptions.add(Rows.bankOnly(ExceptionType.MISSING_IN_LEDGER, b, null, note));
            } else {
                StringBuilder ids = new StringBuilder();
                for (int k = 0; k < candidates.size(); k++) {
                    int i = candidates.get(k);
                    if (ambiguousWith[i] == null) {
                        ambiguousWith[i] = b.lineId();
                    }
                    if (k < 4) {
                        ids.append(k == 0 ? "" : ",").append(ledger.get(i).paymentId());
                    }
                }
                if (candidates.size() > 4) {
                    ids.append(",...");
                }
                exceptions.add(Rows.bankOnly(ExceptionType.AMBIGUOUS_MATCH, b, null,
                        candidates.size() + " payments fit: " + ids));
            }
        }

        for (int i = 0; i < n; i++) {
            if (matched[i]) {
                continue;
            }
            LedgerItem l = ledger.get(i);
            if (ambiguousWith[i] != null) {
                exceptions.add(Rows.ledgerOnly(ExceptionType.PENDING_REVIEW, l,
                        "possible match for ambiguous bank line " + ambiguousWith[i]));
                continue;
            }
            long age = ChronoUnit.DAYS.between(l.date(), cutoff);
            int lag = expectedLagDays(l.bank());
            if (age <= lag + GRACE_DAYS) {
                exceptions.add(Rows.ledgerOnly(ExceptionType.IN_TRANSIT, l,
                        "captured " + age + " day(s) before the cutoff; this bank normally takes " + lag));
            } else {
                exceptions.add(Rows.ledgerOnly(ExceptionType.MISSING_IN_BANK, l,
                        "still not settled " + age + " days after capture"));
            }
        }
        return new Result(matchedRows, exceptions);
    }
}
