package com.payrecon.recon.mr;

import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;

import java.io.IOException;

/**
 * Combiner = a mini-reducer that runs on the map side before data is shuffled over the network.
 * A batch can contain hundreds of payouts; instead of shipping hundreds of records, each mapper ships
 * one partial sum per batch. Adding is associative, so partial sums can be added again later safely.
 * Bank lines are passed through untouched.
 */
public class Pass3Combiner extends Reducer<Text, Text, Text, Text> {

    @Override
    protected void reduce(Text key, Iterable<Text> values, Context context) throws IOException, InterruptedException {
        PayoutTotals totals = new PayoutTotals();
        for (Text value : values) {
            String record = value.toString();
            if (record.startsWith("P\t")) {
                totals.add(record);
            } else {
                context.write(key, new Text(record));
            }
        }
        if (totals.any) {
            context.write(key, new Text(totals.toRecord()));
        }
    }
}
