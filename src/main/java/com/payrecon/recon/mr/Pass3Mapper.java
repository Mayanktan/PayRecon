package com.payrecon.recon.mr;

import com.payrecon.common.Tsv;
import com.payrecon.recon.match.BankItem;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;

import java.io.IOException;

/**
 * Job 3 mapper: keys ledger payouts and bank debits by payout batch id.
 * Ledger payouts are emitted as small "P amount count date bank" records that a combiner can add up.
 */
public class Pass3Mapper extends Mapper<LongWritable, Text, Text, Text> {

    private boolean ledgerInput;
    private final Text outKey = new Text();
    private final Text outValue = new Text();

    @Override
    protected void setup(Context context) {
        String folder = ((FileSplit) context.getInputSplit()).getPath().getParent().getName();
        ledgerInput = "ledger_payouts".equals(folder);
    }

    @Override
    protected void map(LongWritable offset, Text value, Context context) throws IOException, InterruptedException {
        String line = value.toString();
        if (line.isBlank()) {
            return;
        }
        String[] f = Tsv.split(line);
        if (ledgerInput) {
            // payout_id, batch_id, bank, amount, payout_date, merchant_id
            outKey.set(f[1]);
            outValue.set(Tsv.join("P", f[3], 1, f[4], f[2]));
            context.getCounter(ReconCounter.LEDGER_PAYOUTS).increment(1);
        } else {
            BankItem b = BankItem.fromTsv(f, 0);
            boolean batchRef = b.ref() != null && b.ref().startsWith("pob_") && "FULL".equals(b.refQuality());
            outKey.set(batchRef ? b.ref() : "NOREF:" + b.lineId());
            outValue.set("B\t" + line);
        }
        context.write(outKey, outValue);
    }
}
