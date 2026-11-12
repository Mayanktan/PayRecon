package com.payrecon.ledger;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LedgerRulesTest {

    @Test
    void unbalancedEntryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LedgerService.validateBalanced(List.of(
                Leg.debit("A", 100), Leg.credit("B", 99))));
        assertThrows(IllegalArgumentException.class, () -> LedgerService.validateBalanced(List.of(Leg.debit("A", 100))));
        assertThrows(IllegalArgumentException.class, () -> Leg.debit("A", 0)); // postings are always positive
    }

    @Test
    void standardEntriesAlwaysBalance() {
        assertDoesNotThrow(() -> LedgerService.validateBalanced(LedgerService.paymentLegs("M1", "HDFC", 10_000, 200)));
        assertDoesNotThrow(() -> LedgerService.validateBalanced(LedgerService.refundLegs("M1", 5_000)));
        assertDoesNotThrow(() -> LedgerService.validateBalanced(LedgerService.payoutLegs("M1", 9_800)));
    }

    @Test
    void zeroFeePaymentHasNoFeeLeg() {
        assertEquals(2, LedgerService.paymentLegs("M1", "HDFC", 10, 0).size());
        assertEquals(3, LedgerService.paymentLegs("M1", "HDFC", 10_000, 200).size());
    }

    @Test
    void balancesMoveOnTheNormalSide() {
        // assets grow with debits, liabilities grow with credits
        assertEquals(100, AccountType.ASSET.signedDelta(Direction.DEBIT, 100));
        assertEquals(-100, AccountType.ASSET.signedDelta(Direction.CREDIT, 100));
        assertEquals(100, AccountType.LIABILITY.signedDelta(Direction.CREDIT, 100));
        assertEquals(-100, AccountType.LIABILITY.signedDelta(Direction.DEBIT, 100));
        assertEquals(100, AccountType.REVENUE.signedDelta(Direction.CREDIT, 100));
    }
}
