package com.payrecon.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {

    @Test
    void formatsWithIndianGrouping() {
        assertEquals("₹0.00", Money.inr(0));
        assertEquals("₹0.05", Money.inr(5));
        assertEquals("₹999.00", Money.inr(99_900));
        assertEquals("₹1,000.00", Money.inr(100_000));
        assertEquals("₹12,34,567.89", Money.inr(123_456_789));
        assertEquals("₹1,00,00,000.00", Money.inr(1_000_000_000L));
        assertEquals("-₹25.50", Money.inr(-2_550));
    }

    @Test
    void parsesRupeesExactlyWithoutFloatingPoint() {
        assertEquals(123_450, Money.parseRupees("1234.50"));
        assertEquals(123_450, Money.parseRupees("1234.5"));
        assertEquals(10, Money.parseRupees("0.10"));
        assertThrows(ArithmeticException.class, () -> Money.parseRupees("1.005")); // a third decimal is not money
        assertThrows(NumberFormatException.class, () -> Money.parseRupees("12A4"));
    }

    @Test
    void basisPointsRoundHalfUp() {
        assertEquals(200, Money.basisPoints(10_000, 200)); // 2% of ₹100 = ₹2
        assertEquals(1, Money.basisPoints(25, 200));        // 0.5 paise rounds up
        assertEquals(0, Money.basisPoints(24, 200));        // 0.48 paise rounds down
    }
}
