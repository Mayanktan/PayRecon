package com.payrecon.recon.mr;

import com.payrecon.bank.Bank;
import com.payrecon.bank.BankParsers;
import com.payrecon.bank.ParsedLine;
import com.payrecon.common.Tsv;
import com.payrecon.recon.match.LedgerItem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs;

import java.io.IOException;

/**
 * Job 1 mapper. Reads BOTH the ledger extract and the raw bank files (a reduce-side join):
 * it tags each record with its source ("L" or "B") and emits it under the payment reference,
 * so the reducer receives a payment and every bank line with the same reference together.
 *
 * Lines that cannot join by reference are routed to side outputs (MultipleOutputs):
 *   pass2bank/        credits with a partial or missing reference   -> job 2
 *   payoutbank/       debits (payout batches)                       -> job 3
 *   malformed/        unreadable lines (never silently dropped)
 *   bank_normalized/  every parsed line, partitioned by value date  -> Hive
 */
public class Pass1Mapper extends Mapper<LongWritable, Text, Text, Text> {

    private MultipleOutputs<Text, Text> out;
    private Bank bank; // null when this mapper reads the ledger extract
    private String fileName;
    private long lineNo;
    private final Text outKey = new Text();
    private final Text outValue = new Text();

    @Override
    protected void setup(Context context) {
        out = new MultipleOutputs<>(context);
        Path path = ((FileSplit) context.getInputSplit()).getPath();
        fileName = path.getName();
        String folder = path.getParent().getName();
        bank = "ledger_payments".equals(folder) ? null : Bank.valueOf(folder);
        lineNo = 0;
    }

    @Override
    protected void map(LongWritable offset, Text value, Context context) throws IOException, InterruptedException {
        String line = value.toString();
        if (bank == null) {
            if (line.isBlank()) {
                return;
            }
            LedgerItem item = LedgerItem.fromTsv(Tsv.split(line), 0);
            outKey.set(item.paymentId());
            outValue.set("L\t" + line);
            context.write(outKey, outValue);
            context.getCounter(ReconCounter.LEDGER_PAYMENTS).increment(1);
            return;
        }

        lineNo++;
        ParsedLine parsed = BankParsers.parse(bank, fileName, lineNo, line);
        if (parsed.status() == ParsedLine.Status.SKIP) {
            context.getCounter(ReconCounter.HEADER_LINES).increment(1);
            return;
        }
        if (parsed.status() == ParsedLine.Status.MALFORMED) {
            context.getCounter(ReconCounter.MALFORMED_LINES).increment(1);
            out.write("malformed", NullWritable.get(), new Text(Tsv.join(parsed.lineId(), bank, parsed.error(), line)),
                    "malformed/part");
            return;
        }

        context.getCounter(ReconCounter.BANK_LINES).increment(1);
        context.getCounter(ReconCounter.valueOf("BANK_LINES_" + bank)).increment(1);
        String tsv = parsed.toTsv();
        out.write("banknorm", NullWritable.get(), new Text(tsv),
                "bank_normalized/value_date=" + parsed.valueDate() + "/part");

        boolean fullPaymentRef = "FULL".equals(parsed.refQuality()) && parsed.ref().startsWith("pay_");
        if (parsed.isCredit() && fullPaymentRef) {
            outKey.set(parsed.ref());
            outValue.set("B\t" + tsv);
            context.write(outKey, outValue);
        } else if (parsed.isCredit()) {
            out.write("pass2bank", NullWritable.get(), new Text(tsv), "pass2bank/part");
        } else {
            out.write("payoutbank", NullWritable.get(), new Text(tsv), "payoutbank/part");
        }
    }

    @Override
    protected void cleanup(Context context) throws IOException, InterruptedException {
        out.close();
    }
}
