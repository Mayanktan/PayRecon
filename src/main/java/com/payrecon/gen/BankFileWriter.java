package com.payrecon.gen;

import com.payrecon.bank.Bank;
import com.payrecon.bank.BankParsers;
import com.payrecon.common.Money;
import com.payrecon.gen.DataGenerator.Batch;
import com.payrecon.gen.DataGenerator.Options;
import com.payrecon.gen.DataGenerator.Payment;
import com.payrecon.recon.BankFiles;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Writes the settlement files that HDFC (CSV), ICICI (pipe-separated) and CARDNET (JSON lines) send us,
 * one file per bank per day, and plants realistic problems in them. Each planted problem goes to the
 * answer key with the exact line id (BANK:file:line number) the engine will see.
 */
final class BankFileWriter {

    record Summary(int files, long lines, Map<String, Integer> planted) {
    }

    private record Line(String text, String plantedType, String plantedRef) {
    }

    private static final String[] COMPANIES = {"ACME TRADERS", "SHREE BALAJI ENTERPRISES", "NORTHWIND EXPORTS",
        "SURYA LOGISTICS", "KAVERI AGRO"};

    private final Path root;
    private final Path answerKey;
    private final Random rnd;
    private final LocalDate first;
    private final LocalDate monthEnd;
    private final LocalDate last;
    private final Map<String, List<Line>> files = new TreeMap<>(); // "BANK/file name" -> lines
    private final List<String> answers = new ArrayList<>();
    private final Map<String, Integer> planted = new TreeMap<>();

    BankFileWriter(Path dataDir, Options o, Random rnd) {
        this.root = dataDir.resolve("bank_files");
        this.answerKey = dataDir.resolve("answer_key.csv");
        this.rnd = rnd;
        this.first = o.month().atDay(2);
        this.monthEnd = o.month().atEndOfMonth();
        this.last = monthEnd.plusDays(o.extraDays());
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            for (Bank b : Bank.values()) {
                files.put(key(b, d), new ArrayList<>());
            }
        }
    }

    Summary write(List<Payment> payments, List<Batch> batches) throws IOException {
        for (Payment p : payments) {
            settle(p);
        }
        unknownCredits(payments.size());
        malformedLines(payments.size());
        for (Batch b : batches) {
            payoutDebit(b);
        }

        long lines = 0;
        for (Map.Entry<String, List<Line>> e : files.entrySet()) {
            Bank bank = Bank.valueOf(e.getKey().substring(0, e.getKey().indexOf('/')));
            String fileName = e.getKey().substring(e.getKey().indexOf('/') + 1);
            LocalDate fileDate = BankFiles.dateOf(fileName);
            List<Line> content = e.getValue();
            Collections.shuffle(content, rnd);
            Path dir = root.resolve(bank.name());
            Files.createDirectories(dir);
            try (BufferedWriter w = Files.newBufferedWriter(dir.resolve(fileName))) {
                long lineNo = 0;
                if (bank == Bank.HDFC) {
                    w.write(BankParsers.HDFC_HEADER);
                    w.newLine();
                    lineNo++;
                }
                for (Line line : content) {
                    w.write(line.text());
                    w.newLine();
                    lineNo++;
                    if (line.plantedType() != null) {
                        answer(line.plantedType(), line.plantedRef(), BankParsers.lineId(bank, fileName, lineNo), fileDate);
                    }
                }
                lines += lineNo;
            }
        }
        Collections.sort(answers);
        List<String> out = new ArrayList<>();
        out.add("type,ref,bank_line_id,date");
        out.addAll(answers);
        Files.write(answerKey, out);
        return new Summary(files.size(), lines, planted);
    }

    /** One settlement line per payment, with problems planted at small, realistic rates. */
    private void settle(Payment p) {
        LocalDate captured = p.at.toLocalDate();
        int lag = "CARDNET".equals(p.acquirer) ? 2 : 1;
        double fate = rnd.nextDouble();
        if (fate < 0.0005 && !captured.isAfter(monthEnd.minusDays(6))) {
            answer("MISSING_IN_BANK", p.id, null, captured); // the bank never settles it
            return;
        }
        boolean late = rnd.nextDouble() < 0.005;
        LocalDate valueDate = captured.plusDays(lag + (late ? 1 + rnd.nextInt(3) : 0));
        if (valueDate.isAfter(last)) {
            return; // settles after the simulated horizon
        }
        long amount = p.amount;
        String type = null;
        if (fate >= 0.0005 && fate < 0.0015) {
            amount = mismatch(p.amount);
            type = "AMOUNT_MISMATCH";
        }
        boolean duplicate = fate >= 0.0015 && fate < 0.0020 && valueDate.isBefore(last);
        String refStyle = "FULL";
        if (type == null && !duplicate) {
            double r = rnd.nextDouble();
            if ("ICICI".equals(p.acquirer) && r < 0.005) {
                refStyle = "TRUNCATED"; // ICICI's reference field cut to 12 characters
            } else if ("HDFC".equals(p.acquirer) && r < 0.003) {
                refStyle = "NONE"; // UPI credit narration without our reference
            }
        }
        String text = creditLine(p.acquirer, valueDate, p.id, refStyle, amount);
        String plantedType = type != null ? type : ("FULL".equals(refStyle) ? null : "EXPECT_AUTO_MATCH");
        add(Bank.valueOf(p.acquirer), valueDate, new Line(text, plantedType, p.id));
        if (duplicate) { // the bank re-sends the same line in the next day's file
            add(Bank.valueOf(p.acquirer), valueDate.plusDays(1), new Line(text, "DUPLICATE_IN_BANK", p.id));
        }
    }

    private long mismatch(long amount) {
        long result;
        if (rnd.nextBoolean()) {
            long delta = 1 + rnd.nextInt(99);
            result = rnd.nextBoolean() ? amount + delta : Math.max(1, amount - delta); // rounding difference
        } else {
            result = Math.max(1, amount - Math.max(1, Math.round(amount * 0.01))); // a 1% deduction
        }
        return result == amount ? amount + 1 : result;
    }

    private String creditLine(String acquirer, LocalDate date, String payId, String refStyle, long amount) {
        switch (acquirer) {
            case "HDFC": {
                String narration = "NONE".equals(refStyle) ? "UPI CREDIT RRN" + digits(12) : "SETTLEMENT/PAY REF " + payId;
                return date + ",UTR" + digits(10) + "," + narration + "," + Money.rupees(amount) + ",,INR";
            }
            case "ICICI": {
                String ref = "TRUNCATED".equals(refStyle) ? payId.substring(0, 12) : payId;
                return "CR|" + yyyymmdd(date) + "|RRN" + digits(12) + "|" + ref + "|" + String.format("%011d", amount) + "|INR";
            }
            default:
                return cardnetLine(date, payId, amount);
        }
    }

    private String cardnetLine(LocalDate date, String merchantRef, long amount) {
        return String.format("{\"settlement_id\":\"stl_%s\",\"settled_on\":\"%s\",\"txn\":{\"merchant_ref\":\"%s\","
                        + "\"auth_code\":\"%06d\"},\"gross\":{\"value\":\"%s\",\"currency\":\"INR\"},"
                        + "\"fees\":{\"interchange\":\"0.00\",\"scheme\":\"0.00\"}}",
                digits(10), date, merchantRef, rnd.nextInt(1_000_000), Money.rupees(amount));
    }

    /** Money from the bank that matches no payment (a wrong reference, or none at all). */
    private void unknownCredits(int paymentCount) {
        int count = Math.max(3, (int) Math.round(paymentCount * 0.0003));
        for (int i = 0; i < count; i++) {
            Bank bank = Bank.values()[rnd.nextInt(3)];
            LocalDate date = randomDate();
            long amount = Math.round(Math.exp(7.5 + rnd.nextGaussian()) * 100) + rnd.nextInt(100) + 1;
            boolean fakeRef = rnd.nextBoolean();
            String fake = String.format("pay_9%09d", rnd.nextInt(1_000_000_000));
            String text = switch (bank) {
                case HDFC -> date + ",UTR" + digits(10) + ","
                        + (fakeRef ? "SETTLEMENT/PAY REF " + fake : "NEFT CR FROM " + COMPANIES[rnd.nextInt(COMPANIES.length)])
                        + "," + Money.rupees(amount) + ",,INR";
                case ICICI -> "CR|" + yyyymmdd(date) + "|RRN" + digits(12) + "|" + (fakeRef ? fake : "NEFT" + digits(8))
                        + "|" + String.format("%011d", amount) + "|INR";
                default -> cardnetLine(date, fakeRef ? fake : "UNKNOWN-" + digits(6), amount);
            };
            add(bank, date, new Line(text, "MISSING_IN_LEDGER", null));
        }
    }

    /** Lines the parser cannot read: a missing column, letters in an amount, cut-off JSON. */
    private void malformedLines(int paymentCount) {
        int count = Math.max(2, (int) Math.round(paymentCount * 0.0001));
        for (int i = 0; i < count; i++) {
            Bank bank = Bank.values()[rnd.nextInt(3)];
            LocalDate date = randomDate();
            String someRef = String.format("pay_%010d", 1 + rnd.nextInt(Math.max(1, paymentCount)));
            String text = switch (bank) {
                case HDFC -> date + ",UTR" + digits(10) + ",SETTLEMENT/PAY REF " + someRef;
                case ICICI -> "CR|" + yyyymmdd(date) + "|RRN" + digits(12) + "|" + someRef + "|12A4500|INR";
                default -> "{\"settlement_id\":\"stl_" + digits(10) + "\",\"settled_on\":\"" + date + "\",\"txn\":{\"merchant_ref\":";
            };
            add(bank, date, new Line(text, "MALFORMED_BANK_LINE", null));
        }
    }

    /** One bank debit per payout batch, on the payout date. */
    private void payoutDebit(Batch b) {
        double r = rnd.nextDouble();
        if (r < 0.02 && !b.date.isAfter(monthEnd.minusDays(2))) {
            answer("PAYOUT_NOT_EXECUTED", b.id, null, b.date);
            return;
        }
        long amount = b.total;
        String type = null;
        if (r >= 0.02 && r < 0.05) {
            amount = b.total + 2_500; // the bank also took a ₹25 charge
            type = "PAYOUT_BATCH_MISMATCH";
        }
        String text = "HDFC".equals(b.bank)
                ? b.date + ",UTR" + digits(10) + ",PAYOUT BATCH " + b.id + ",," + Money.rupees(amount) + ",INR"
                : "DR|" + yyyymmdd(b.date) + "|RRN" + digits(12) + "|" + b.id + "|" + String.format("%011d", amount) + "|INR";
        add(Bank.valueOf(b.bank), b.date, new Line(text, type, b.id));
    }

    // ------------------------------------------------------------------ helpers

    private void add(Bank bank, LocalDate date, Line line) {
        List<Line> list = files.get(key(bank, date));
        if (list != null) {
            list.add(line);
        }
    }

    private void answer(String type, String ref, String lineId, LocalDate date) {
        answers.add(String.join(",", type, ref == null ? "" : ref, lineId == null ? "" : lineId, date.toString()));
        planted.merge(type, 1, Integer::sum);
    }

    private LocalDate randomDate() {
        return first.plusDays(rnd.nextInt((int) ChronoUnit.DAYS.between(first, last) + 1));
    }

    private String digits(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append((char) ('0' + rnd.nextInt(10)));
        }
        return sb.toString();
    }

    private static String yyyymmdd(LocalDate date) {
        return date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private static String key(Bank bank, LocalDate date) {
        String name = switch (bank) {
            case HDFC -> "HDFC_STMT_" + yyyymmdd(date) + ".csv";
            case ICICI -> "ICICI_" + yyyymmdd(date) + ".txt";
            default -> "cardnet_settlement_" + date + ".jsonl";
        };
        return bank.name() + "/" + name;
    }
}
