package com.payrecon.bank;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrecon.common.Money;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one raw line of a bank file into a {@link ParsedLine}. This is the ONLY parser in the project:
 * the MapReduce jobs and the MongoDB loader both call it, so parsing rules can never drift apart.
 * A line that cannot be read is never dropped silently: it comes back as MALFORMED with a reason.
 */
public final class BankParsers {

    public static final String HDFC_HEADER = "value_date,utr,narration,credit_amount,debit_amount,currency";

    private static final Pattern PAY_REF = Pattern.compile("pay_\\d{1,10}");
    private static final Pattern BATCH_REF = Pattern.compile("pob_\\d{8}_[A-Z]+");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final int FULL_PAY_REF_LENGTH = 14; // "pay_" + 10 digits
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private BankParsers() {
    }

    /** Every bank line gets a stable id: BANK:file name:physical line number (counted from 1). */
    public static String lineId(Bank bank, String fileName, long lineNo) {
        return bank + ":" + fileName + ":" + lineNo;
    }

    public static ParsedLine parse(Bank bank, String fileName, long lineNo, String raw) {
        String id = lineId(bank, fileName, lineNo);
        try {
            switch (bank) {
                case HDFC:
                    return parseHdfc(id, raw);
                case ICICI:
                    return parseIcici(id, raw);
                default:
                    return parseCardnet(id, raw);
            }
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ParsedLine.malformed(id, bank, reason.length() > 200 ? reason.substring(0, 200) : reason);
        }
    }

    /** HDFC: value_date,utr,narration,credit_amount,debit_amount,currency  (amounts in rupees) */
    private static ParsedLine parseHdfc(String id, String raw) {
        if (raw.startsWith("value_date,")) {
            return ParsedLine.skip(id, Bank.HDFC);
        }
        String[] f = raw.split(",", -1);
        if (f.length != 6) {
            return ParsedLine.malformed(id, Bank.HDFC, "expected 6 comma-separated columns, found " + f.length);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("value_date", f[0]);
        fields.put("utr", f[1]);
        fields.put("narration", f[2]);
        fields.put("credit_amount", f[3]);
        fields.put("debit_amount", f[4]);
        fields.put("currency", f[5]);

        LocalDate date = LocalDate.parse(f[0]);
        requireInr(f[5]);
        String direction;
        long amount;
        if (!f[3].isEmpty() && f[4].isEmpty()) {
            direction = "CREDIT";
            amount = Money.parseRupees(f[3]);
        } else if (f[3].isEmpty() && !f[4].isEmpty()) {
            direction = "DEBIT";
            amount = Money.parseRupees(f[4]);
        } else {
            throw new IllegalArgumentException("exactly one of credit_amount / debit_amount must be filled");
        }
        requirePositive(amount);
        Ref ref = extractRef(f[2]);
        return new ParsedLine(ParsedLine.Status.OK, id, Bank.HDFC, direction, ref.value(), ref.quality(), amount,
                f[1], date, fields, null);
    }

    /** ICICI: CR|20261002|RRN123456789012|pay_0000000123|00000123450|INR  (amount in paise, zero-padded) */
    private static ParsedLine parseIcici(String id, String raw) {
        String[] f = raw.split("\\|", -1);
        if (f.length != 6) {
            return ParsedLine.malformed(id, Bank.ICICI, "expected 6 pipe-separated fields, found " + f.length);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("txn_type", f[0]);
        fields.put("txn_date", f[1]);
        fields.put("rrn", f[2]);
        fields.put("reference", f[3]);
        fields.put("amount_paise", f[4]);
        fields.put("currency", f[5]);

        String direction;
        if ("CR".equals(f[0])) {
            direction = "CREDIT";
        } else if ("DR".equals(f[0])) {
            direction = "DEBIT";
        } else {
            throw new IllegalArgumentException("txn_type must be CR or DR, found " + f[0]);
        }
        LocalDate date = LocalDate.parse(f[1], DateTimeFormatter.BASIC_ISO_DATE);
        if (!DIGITS.matcher(f[4]).matches()) {
            throw new IllegalArgumentException("amount_paise is not a whole number: " + f[4]);
        }
        long amount = Long.parseLong(f[4]);
        requirePositive(amount);
        requireInr(f[5]);
        Ref ref = extractRef(f[3]);
        return new ParsedLine(ParsedLine.Status.OK, id, Bank.ICICI, direction, ref.value(), ref.quality(), amount,
                f[2], date, fields, null);
    }

    /** CARDNET: one JSON object per line, with nested "txn", "gross" and "fees" objects. Credits only. */
    private static ParsedLine parseCardnet(String id, String raw) throws Exception {
        JsonNode node = JSON.readTree(raw);
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("not a JSON object");
        }
        String settlementId = text(node, "settlement_id");
        LocalDate date = LocalDate.parse(text(node, "settled_on"));
        String merchantRef = text(node.path("txn"), "merchant_ref");
        long amount = Money.parseRupees(text(node.path("gross"), "value"));
        requireInr(text(node.path("gross"), "currency"));
        requirePositive(amount);
        Map<String, Object> fields = JSON.convertValue(node, MAP_TYPE);
        Ref ref = extractRef(merchantRef);
        return new ParsedLine(ParsedLine.Status.OK, id, Bank.CARDNET, "CREDIT", ref.value(), ref.quality(), amount,
                settlementId, date, fields, null);
    }

    record Ref(String value, String quality) {
    }

    /** Finds a payout batch id (pob_...) or a payment id (pay_...) inside free text. */
    static Ref extractRef(String text) {
        if (text == null) {
            return new Ref(null, "NONE");
        }
        Matcher batch = BATCH_REF.matcher(text);
        if (batch.find()) {
            return new Ref(batch.group(), "FULL");
        }
        Matcher pay = PAY_REF.matcher(text);
        if (pay.find()) {
            String ref = pay.group();
            return new Ref(ref, ref.length() == FULL_PAY_REF_LENGTH ? "FULL" : "PARTIAL");
        }
        return new Ref(null, "NONE");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            throw new IllegalArgumentException("missing field " + field);
        }
        return value.asText();
    }

    private static void requireInr(String currency) {
        if (!"INR".equals(currency)) {
            throw new IllegalArgumentException("unsupported currency " + currency);
        }
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
