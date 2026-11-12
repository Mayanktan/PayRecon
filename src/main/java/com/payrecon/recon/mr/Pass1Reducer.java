package com.payrecon.recon.mr;

import com.payrecon.common.Tsv;
import com.payrecon.recon.match.BankItem;
import com.payrecon.recon.match.ExactRefMatcher;
import com.payrecon.recon.match.LedgerItem;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Job 1 reducer: one call per payment reference. The matching rules live in {@link ExactRefMatcher}. */
public class Pass1Reducer extends Reducer<Text, Text, NullWritable, Text> {

    private MultipleOutputs<NullWritable, Text> out;

    @Override
    protected void setup(Context context) {
        out = new MultipleOutputs<>(context);
    }

    @Override
    protected void reduce(Text key, Iterable<Text> values, Context context) throws IOException, InterruptedException {
        List<LedgerItem> ledger = new ArrayList<>(1);
        List<BankItem> bank = new ArrayList<>(2);
        for (Text value : values) { // Hadoop reuses the Text object, so convert to String right away
            String[] f = Tsv.split(value.toString());
            if ("L".equals(f[0])) {
                ledger.add(LedgerItem.fromTsv(f, 1));
            } else {
                bank.add(BankItem.fromTsv(f, 1));
            }
        }
        ExactRefMatcher.Result result = ExactRefMatcher.match(ledger, bank);
        for (String row : result.matched()) {
            out.write("matched", NullWritable.get(), new Text(row), "matched/part");
        }
        for (String row : result.exceptions()) {
            out.write("exceptions", NullWritable.get(), new Text(row), "exceptions/part");
        }
        for (LedgerItem item : result.unmatchedLedger()) {
            out.write("unmatchedledger", NullWritable.get(), new Text(item.toTsv()), "unmatchedledger/part");
        }
    }

    @Override
    protected void cleanup(Context context) throws IOException, InterruptedException {
        out.close();
    }
}
