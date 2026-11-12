package com.payrecon.bank;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankParsersTest {

    @Test
    void hdfcHeaderIsSkipped() {
        ParsedLine p = BankParsers.parse(Bank.HDFC, "HDFC_STMT_20261002.csv", 1, BankParsers.HDFC_HEADER);
        assertEquals(ParsedLine.Status.SKIP, p.status());
    }

    @Test
    void hdfcCreditWithFullReference() {
        ParsedLine p = BankParsers.parse(Bank.HDFC, "HDFC_STMT_20261002.csv", 7,
                "2026-10-02,UTR0000012345,SETTLEMENT/PAY REF pay_0000000123,1234.50,,INR");
        assertEquals(ParsedLine.Status.OK, p.status());
        assertEquals("HDFC:HDFC_STMT_20261002.csv:7", p.lineId());
        assertEquals("CREDIT", p.direction());
        assertEquals("pay_0000000123", p.ref());
        assertEquals("FULL", p.refQuality());
        assertEquals(123_450, p.amountMinor());
        assertEquals(LocalDate.of(2026, 10, 2), p.valueDate());
        assertEquals("SETTLEMENT/PAY REF pay_0000000123", p.fields().get("narration")); // bank's own field names kept
    }

    @Test
    void hdfcCreditWithoutReference() {
        ParsedLine p = BankParsers.parse(Bank.HDFC, "f.csv", 2, "2026-10-02,UTR1,UPI CREDIT RRN601234567890,500.00,,INR");
        assertEquals(ParsedLine.Status.OK, p.status());
        assertNull(p.ref());
        assertEquals("NONE", p.refQuality());
    }

    @Test
    void hdfcPayoutDebitCarriesBatchReference() {
        ParsedLine p = BankParsers.parse(Bank.HDFC, "f.csv", 3, "2026-10-05,UTR2,PAYOUT BATCH pob_20261005_HDFC,,98765.43,INR");
        assertEquals("DEBIT", p.direction());
        assertEquals("pob_20261005_HDFC", p.ref());
        assertEquals(9_876_543, p.amountMinor());
    }

    @Test
    void hdfcMissingColumnIsMalformedNotDropped() {
        ParsedLine p = BankParsers.parse(Bank.HDFC, "f.csv", 4, "2026-10-12,UTR1234567890,SETTLEMENT/PAY REF pay_0000012345");
        assertEquals(ParsedLine.Status.MALFORMED, p.status());
        assertTrue(p.error().contains("6"));
    }

    @Test
    void iciciTruncatedReferenceIsPartial() {
        ParsedLine p = BankParsers.parse(Bank.ICICI, "ICICI_20261002.txt", 1, "CR|20261002|RRN601234567890|pay_00001234|00000050000|INR");
        assertEquals(ParsedLine.Status.OK, p.status());
        assertEquals("pay_00001234", p.ref());
        assertEquals("PARTIAL", p.refQuality());
        assertEquals(50_000, p.amountMinor()); // ICICI sends paise
    }

    @Test
    void iciciLettersInAmountAreMalformed() {
        ParsedLine p = BankParsers.parse(Bank.ICICI, "f.txt", 1, "CR|20261012|RRN1|pay_0000012345|12A4500|INR");
        assertEquals(ParsedLine.Status.MALFORMED, p.status());
    }

    @Test
    void cardnetJsonKeepsNestedFields() {
        String json = "{\"settlement_id\":\"stl_1\",\"settled_on\":\"2026-10-03\",\"txn\":{\"merchant_ref\":\"pay_0000000042\","
                + "\"auth_code\":\"123456\"},\"gross\":{\"value\":\"10.00\",\"currency\":\"INR\"},\"fees\":{\"interchange\":\"0.00\"}}";
        ParsedLine p = BankParsers.parse(Bank.CARDNET, "cardnet_settlement_2026-10-03.jsonl", 9, json);
        assertEquals(ParsedLine.Status.OK, p.status());
        assertEquals("pay_0000000042", p.ref());
        assertEquals(1_000, p.amountMinor());
        assertTrue(p.fields().get("txn") instanceof Map); // nested object stays nested (stored as-is in MongoDB)
    }

    @Test
    void cardnetTruncatedJsonIsMalformed() {
        ParsedLine p = BankParsers.parse(Bank.CARDNET, "f.jsonl", 1, "{\"settlement_id\":\"stl_1\",\"txn\":{\"merchant_ref\":");
        assertEquals(ParsedLine.Status.MALFORMED, p.status());
    }
}
