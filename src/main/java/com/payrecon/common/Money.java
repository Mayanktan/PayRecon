package com.payrecon.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money helpers. All amounts are stored as whole paise in a {@code long} (₹12.50 = 1250).
 * We never use float/double for money, because 0.1 + 0.2 is not exactly 0.3 in binary floating point.
 */
public final class Money {

    private Money() {
    }

    /**
     * 12345678 paise -> "₹1,23,456.78". Indian grouping (lakh, crore): the last 3 digits, then groups of 2.
     * Java's NumberFormat cannot do this (it only repeats one group size), so we build the string ourselves.
     */
    public static String inr(long paise) {
        long abs = Math.abs(paise);
        String digits = Long.toString(abs / 100);
        StringBuilder grouped = new StringBuilder();
        int len = digits.length();
        if (len <= 3) {
            grouped.append(digits);
        } else {
            String rest = digits.substring(0, len - 3);
            int i = rest.length();
            while (i > 2) {
                grouped.insert(0, "," + rest.substring(i - 2, i));
                i -= 2;
            }
            grouped.insert(0, rest.substring(0, i)).append(',').append(digits.substring(len - 3));
        }
        long p = abs % 100;
        return (paise < 0 ? "-₹" : "₹") + grouped + (p < 10 ? ".0" : ".") + p;
    }

    /** "1234.50" -> 123450. Throws if the text has more than 2 decimals or is not a number. */
    public static long parseRupees(String text) {
        BigDecimal value = new BigDecimal(text.trim());
        return value.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    /** 123450 -> "1234.50" (plain, no currency symbol). */
    public static String rupees(long paise) {
        return BigDecimal.valueOf(paise, 2).toPlainString();
    }

    /**
     * amount × bps / 10,000, rounded half-up, using only integers.
     * bps = basis points: 1 bps = 0.01%, so 200 bps = 2%.
     */
    public static long basisPoints(long amount, int bps) {
        return (amount * bps + 5_000) / 10_000;
    }
}
