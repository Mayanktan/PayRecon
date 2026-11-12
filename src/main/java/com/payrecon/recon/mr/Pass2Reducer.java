package com.payrecon.recon.mr;

import com.payrecon.common.Tsv;
import com.payrecon.recon.match.BankItem;
import com.payrecon.recon.match.LedgerItem;
import com.payrecon.recon.match.WindowMatcher;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Job 2 reducer: one call per bank+amount, records arrive sorted by date. Rules live in {@link WindowMatcher}. */
public class Pass2Reducer extends Reducer<WindowKey, Text, NullWritable, Text> {

    private MultipleOutputs<NullWritable, Text> out;
    private LocalDate cutoff;

    @Override
    protected void setup(Context context) {
        out = new MultipleOutputs<>(context);
        cutoff = LocalDate.parse(context.getConfiguration().get(ReconJobs.CUTOFF_KEY));
    }

    @Override
    protected void reduce(WindowKey key, Iterable<Text> values, Context context) throws IOException, InterruptedException {
        List<LedgerItem> ledger = new ArrayList<>();
        List<BankItem> bank = new ArrayList<>();
        for (Text value : values) {
            String[] f = Tsv.split(value.toString());
            if ("L".equals(f[0])) {
                ledger.add(LedgerItem.fromTsv(f, 1));
            } else {
                bank.add(BankItem.fromTsv(f, 1));
            }
        }
        WindowMatcher.Result result = WindowMatcher.match(ledger, bank, cutoff);
        for (String row : result.matched()) {
            out.write("matched", NullWritable.get(), new Text(row), "matched/part");
        }
        for (String row : result.exceptions()) {
            out.write("exceptions", NullWritable.get(), new Text(row), "exceptions/part");
        }
    }

    @Override
    protected void cleanup(Context context) throws IOException, InterruptedException {
        out.close();
    }
}
