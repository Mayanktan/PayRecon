package com.payrecon.recon.match;

import com.payrecon.common.Tsv;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatcherTest {

    private static final LocalDate OCT_10 = LocalDate.of(2026, 10, 10);

    private static LedgerItem pay(String id, long amount, LocalDate date) {
        return new LedgerItem(id, "M00001", "HDFC", amount, date);
    }

    private static BankItem credit(String lineId, String ref, String quality, long amount, LocalDate date) {
        return new BankItem(lineId, "HDFC", "CREDIT", ref, quality, amount, "UTR1", date);
    }

    private static String type(String row) {
        return Tsv.split(row)[0];
    }

    // ------------------------------------------------------------------ pass 1

    @Test
    void exactReferenceAndAmountMatches() {
        var r = ExactRefMatcher.match(List.of(pay("pay_0000000001", 500, OCT_10)),
                List.of(credit("HDFC:f:2", "pay_0000000001", "FULL", 500, OCT_10.plusDays(1))));
        assertEquals(1, r.matched().size());
        assertTrue(r.exceptions().isEmpty());
    }

    @Test
    void differentAmountIsMatchedButFlagged() {
        var r = ExactRefMatcher.match(List.of(pay("pay_0000000001", 500, OCT_10)),
                List.of(credit("HDFC:f:2", "pay_0000000001", "FULL", 450, OCT_10.plusDays(1))));
        assertEquals(1, r.matched().size());
        assertEquals("AMOUNT_MISMATCH", type(r.exceptions().get(0)));
        assertEquals("50", Tsv.split(r.exceptions().get(0))[8]); // diff = ledger - bank
    }

    @Test
    void duplicateUsesNumericLineOrderNotTextOrder() {
        // as text, "f:10" sorts before "f:9"; physically, line 9 came first and is the original
        var r = ExactRefMatcher.match(List.of(pay("pay_0000000001", 500, OCT_10)), List.of(
                credit("HDFC:f:10", "pay_0000000001", "FULL", 500, OCT_10),
                credit("HDFC:f:9", "pay_0000000001", "FULL", 500, OCT_10)));
        assertEquals("HDFC:f:9", Tsv.split(r.matched().get(0))[2]);
        assertEquals("DUPLICATE_IN_BANK", type(r.exceptions().get(0)));
        assertEquals("HDFC:f:10", Tsv.split(r.exceptions().get(0))[3]);
    }

    @Test
    void unknownReferenceIsMissingInLedger() {
        var r = ExactRefMatcher.match(List.of(), List.of(credit("HDFC:f:2", "pay_9999999999", "FULL", 500, OCT_10)));
        assertEquals("MISSING_IN_LEDGER", type(r.exceptions().get(0)));
    }

    @Test
    void paymentWithoutBankLineGoesToPassTwo() {
        var r = ExactRefMatcher.match(List.of(pay("pay_0000000001", 500, OCT_10)), List.of());
        assertEquals(1, r.unmatchedLedger().size());
    }

    // ------------------------------------------------------------------ pass 2

    @Test
    void singleCandidateInWindowMatches() {
        var r = WindowMatcher.match(List.of(pay("pay_0000000007", 1_234, OCT_10)),
                List.of(credit("HDFC:f:5", null, "NONE", 1_234, OCT_10.plusDays(1))), OCT_10.plusDays(20));
        assertEquals(1, r.matched().size());
        assertEquals(Rows.PASS_WINDOW, type(r.matched().get(0)));
    }

    @Test
    void partialReferenceNarrowsTheCandidates() {
        var r = WindowMatcher.match(List.of(pay("pay_0000001234", 500, OCT_10), pay("pay_0000005678", 500, OCT_10)),
                List.of(credit("HDFC:f:5", "pay_00000012", "PARTIAL", 500, OCT_10.plusDays(1))), OCT_10.plusDays(20));
        assertEquals(1, r.matched().size());
        assertEquals("pay_0000001234", Tsv.split(r.matched().get(0))[1]);
    }

    @Test
    void twoEqualCandidatesAreNeverGuessed() {
        var r = WindowMatcher.match(List.of(pay("pay_0000000001", 50_000, OCT_10), pay("pay_0000000002", 50_000, OCT_10)),
                List.of(credit("HDFC:f:5", null, "NONE", 50_000, OCT_10.plusDays(1))), OCT_10.plusDays(20));
        assertTrue(r.matched().isEmpty());
        long ambiguous = r.exceptions().stream().filter(x -> type(x).equals("AMBIGUOUS_MATCH")).count();
        long review = r.exceptions().stream().filter(x -> type(x).equals("PENDING_REVIEW")).count();
        assertEquals(1, ambiguous);
        assertEquals(2, review);
    }

    @Test
    void leftoverPaymentIsInTransitWhenYoungAndMissingWhenOld() {
        LocalDate cutoff = OCT_10.plusDays(2);
        var young = WindowMatcher.match(List.of(pay("pay_0000000001", 500, OCT_10)), List.of(), cutoff);
        assertEquals("IN_TRANSIT", type(young.exceptions().get(0)));
        var old = WindowMatcher.match(List.of(pay("pay_0000000001", 500, OCT_10)), List.of(), OCT_10.plusDays(10));
        assertEquals("MISSING_IN_BANK", type(old.exceptions().get(0)));
    }

    @Test
    void paymentOlderThanTheWindowIsNotACandidate() {
        var r = WindowMatcher.match(List.of(pay("pay_0000000001", 500, OCT_10.minusDays(10))),
                List.of(credit("HDFC:f:5", null, "NONE", 500, OCT_10)), OCT_10.plusDays(20));
        assertTrue(r.matched().isEmpty());
        assertTrue(r.exceptions().stream().anyMatch(x -> type(x).equals("MISSING_IN_LEDGER")));
        assertTrue(r.exceptions().stream().anyMatch(x -> type(x).equals("MISSING_IN_BANK")));
    }

    // ------------------------------------------------------------------ pass 3

    @Test
    void payoutBatchTotals() {
        BankItem debit = new BankItem("HDFC:f:3", "HDFC", "DEBIT", "pob_20261005_HDFC", "FULL", 10_000, "UTR", OCT_10);
        var equal = PayoutBatchMatcher.match("pob_20261005_HDFC", 10_000L, 3, OCT_10, "HDFC", List.of(debit), OCT_10.plusDays(5));
        assertEquals(1, equal.matched().size());
        assertTrue(equal.exceptions().isEmpty());

        var different = PayoutBatchMatcher.match("pob_20261005_HDFC", 7_500L, 3, OCT_10, "HDFC", List.of(debit), OCT_10.plusDays(5));
        assertEquals("PAYOUT_BATCH_MISMATCH", type(different.exceptions().get(0)));

        var missing = PayoutBatchMatcher.match("pob_20261005_HDFC", 10_000L, 3, OCT_10, "HDFC", List.of(), OCT_10.plusDays(1));
        assertEquals("PAYOUT_NOT_EXECUTED", type(missing.exceptions().get(0)));
        var notDue = PayoutBatchMatcher.match("pob_20261005_HDFC", 10_000L, 3, OCT_10, "HDFC", List.of(), OCT_10);
        assertEquals("PAYOUT_PENDING", type(notDue.exceptions().get(0)));

        var unknown = PayoutBatchMatcher.match("NOREF:HDFC:f:3", null, 0, null, null, List.of(debit), OCT_10);
        assertEquals("UNKNOWN_DEBIT", type(unknown.exceptions().get(0)));
    }
}
