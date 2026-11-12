package com.payrecon.recon.mr;

import com.payrecon.common.Tsv;
import com.payrecon.recon.match.BankItem;
import com.payrecon.recon.match.PayoutBatchMatcher;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Job 3 reducer: one call per payout batch. Rules live in {@link PayoutBatchMatcher}. */
public class Pass3Reducer extends Reducer<Text, Text, NullWritable, Text> {

    private MultipleOutputs<NullWritable, Text> out;
    private LocalDate cutoff;

    @Override
    protected void setup(Context context) {
        out = new MultipleOutputs<>(context);
        cutoff = LocalDate.parse(context.getConfiguration().get(ReconJobs.CUTOFF_KEY));
    }

    @Override
    protected void reduce(Text key, Iterable<Text> values, Context context) throws IOException, InterruptedException {
        PayoutTotals totals = new PayoutTotals();
        List<BankItem> bank = new ArrayList<>();
        for (Text value : values) {
            String record = value.toString();
            if (record.startsWith("P\t")) {
                totals.add(record);
            } else {
                bank.add(BankItem.fromTsv(Tsv.split(record), 1));
            }
        }
        PayoutBatchMatcher.Result result = PayoutBatchMatcher.match(key.toString(),
                totals.any ? totals.total : null, (int) totals.count,
                totals.any ? LocalDate.parse(totals.earliestDate) : null, totals.bank, bank, cutoff);
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
