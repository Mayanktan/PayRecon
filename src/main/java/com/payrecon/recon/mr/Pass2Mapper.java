package com.payrecon.recon.mr;

import com.payrecon.common.Tsv;
import com.payrecon.recon.match.BankItem;
import com.payrecon.recon.match.LedgerItem;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;

import java.io.IOException;

/** Job 2 mapper: keys every leftover payment and bank line by (bank|amount, date) for the secondary sort. */
public class Pass2Mapper extends Mapper<LongWritable, Text, WindowKey, Text> {

    private boolean ledgerInput;
    private final WindowKey key = new WindowKey();
    private final Text out = new Text();

    @Override
    protected void setup(Context context) {
        String folder = ((FileSplit) context.getInputSplit()).getPath().getParent().getName();
        ledgerInput = "unmatchedledger".equals(folder);
    }

    @Override
    protected void map(LongWritable offset, Text value, Context context) throws IOException, InterruptedException {
        String line = value.toString();
        if (line.isBlank()) {
            return;
        }
        String[] f = Tsv.split(line);
        if (ledgerInput) {
            LedgerItem l = LedgerItem.fromTsv(f, 0);
            key.set(l.bank() + "|" + l.amount(), (int) l.date().toEpochDay(), WindowKey.LEDGER, l.paymentId());
            out.set("L\t" + line);
        } else {
            BankItem b = BankItem.fromTsv(f, 0);
            key.set(b.bank() + "|" + b.amount(), (int) b.date().toEpochDay(), WindowKey.BANK, b.lineId());
            out.set("B\t" + line);
        }
        context.write(key, out);
    }
}
