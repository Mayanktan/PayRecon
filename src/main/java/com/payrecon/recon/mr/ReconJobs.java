package com.payrecon.recon.mr;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.LazyOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;

import java.nio.file.Files;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds and runs the three MapReduce jobs as a small DAG (a graph of steps with dependencies):
 *
 *   job 1 (exact reference)  ──► job 2 (amount + date window)
 *                            └─► job 3 (payout batches)
 *
 * Jobs 2 and 3 both depend only on job 1, so they run at the same time.
 * Hadoop runs in "local" mode: the whole MapReduce framework runs inside this JVM, no cluster needed.
 */
public final class ReconJobs {

    public static final String CUTOFF_KEY = "payrecon.cutoff";

    public record Inputs(java.nio.file.Path ledgerPayments, java.nio.file.Path ledgerPayouts,
                         List<java.nio.file.Path> bankFiles, java.nio.file.Path pass1Out,
                         java.nio.file.Path pass2Out, java.nio.file.Path pass3Out) {
    }

    public record Outcome(Map<String, Long> counters, long pass1Millis, long pass2and3Millis) {
    }

    private ReconJobs() {
    }

    public static Configuration configuration(LocalDate cutoff) {
        Configuration conf = new Configuration();
        conf.set("fs.defaultFS", "file:///");
        conf.set("mapreduce.framework.name", "local");
        // our project path contains a space; keep Hadoop's scratch space in a simple path
        conf.set("hadoop.tmp.dir", "/tmp/payrecon-hadoop-" + System.getProperty("user.name"));
        conf.setInt("mapreduce.local.map.tasks.maximum", 4);
        conf.setInt("mapreduce.local.reduce.tasks.maximum", 4);
        conf.set(CUTOFF_KEY, cutoff.toString());
        return conf;
    }

    public static Outcome run(Configuration conf, Inputs in) throws Exception {
        long t0 = System.nanoTime();
        Job pass1 = pass1(conf, in);
        if (!pass1.waitForCompletion(false)) {
            throw new IllegalStateException("MapReduce job 1 (exact reference) failed");
        }
        long t1 = System.nanoTime();

        // Jobs 2 and 3 read folders that job 1 writes only when it has data; an input folder must exist.
        for (String dir : List.of("unmatchedledger", "pass2bank", "payoutbank")) {
            Files.createDirectories(in.pass1Out().resolve(dir));
        }
        Files.createDirectories(in.ledgerPayouts());

        Job pass2 = pass2(conf, in);
        Job pass3 = pass3(conf, in);
        pass2.submit();
        pass3.submit();
        while (!pass2.isComplete() || !pass3.isComplete()) {
            Thread.sleep(200);
        }
        if (!pass2.isSuccessful()) {
            throw new IllegalStateException("MapReduce job 2 (amount + date window) failed");
        }
        if (!pass3.isSuccessful()) {
            throw new IllegalStateException("MapReduce job 3 (payout batches) failed");
        }
        long t2 = System.nanoTime();

        Map<String, Long> counters = new LinkedHashMap<>();
        for (ReconCounter c : ReconCounter.values()) {
            long value = pass1.getCounters().findCounter(c).getValue() + pass3.getCounters().findCounter(c).getValue();
            counters.put(c.name(), value);
        }
        return new Outcome(counters, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000);
    }

    private static Job pass1(Configuration conf, Inputs in) throws Exception {
        Job job = Job.getInstance(conf, "payrecon-1-exact-reference");
        job.setJarByClass(ReconJobs.class);
        job.setInputFormatClass(NonSplittableTextInputFormat.class);
        FileInputFormat.addInputPath(job, hadoopPath(in.ledgerPayments()));
        for (java.nio.file.Path file : in.bankFiles()) {
            FileInputFormat.addInputPath(job, hadoopPath(file));
        }
        job.setMapperClass(Pass1Mapper.class);
        job.setReducerClass(Pass1Reducer.class);
        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(Text.class);
        job.setNumReduceTasks(4);
        output(job, in.pass1Out(),
                List.of("matched", "exceptions", "unmatchedledger", "pass2bank", "payoutbank", "banknorm", "malformed"));
        return job;
    }

    private static Job pass2(Configuration conf, Inputs in) throws Exception {
        Job job = Job.getInstance(conf, "payrecon-2-amount-date-window");
        job.setJarByClass(ReconJobs.class);
        FileInputFormat.addInputPath(job, hadoopPath(in.pass1Out().resolve("unmatchedledger")));
        FileInputFormat.addInputPath(job, hadoopPath(in.pass1Out().resolve("pass2bank")));
        job.setMapperClass(Pass2Mapper.class);
        job.setMapOutputKeyClass(WindowKey.class);
        job.setMapOutputValueClass(Text.class);
        job.setPartitionerClass(WindowPartitioner.class);              // which reducer
        job.setGroupingComparatorClass(WindowGroupingComparator.class); // which records share one reduce() call
        job.setReducerClass(Pass2Reducer.class);
        job.setNumReduceTasks(4);
        output(job, in.pass2Out(), List.of("matched", "exceptions"));
        return job;
    }

    private static Job pass3(Configuration conf, Inputs in) throws Exception {
        Job job = Job.getInstance(conf, "payrecon-3-payout-batches");
        job.setJarByClass(ReconJobs.class);
        FileInputFormat.addInputPath(job, hadoopPath(in.ledgerPayouts()));
        FileInputFormat.addInputPath(job, hadoopPath(in.pass1Out().resolve("payoutbank")));
        job.setMapperClass(Pass3Mapper.class);
        job.setCombinerClass(Pass3Combiner.class);
        job.setReducerClass(Pass3Reducer.class);
        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(Text.class);
        job.setNumReduceTasks(2);
        output(job, in.pass3Out(), List.of("matched", "exceptions"));
        return job;
    }

    /** All real output goes through named MultipleOutputs; LazyOutputFormat avoids empty default part files. */
    private static void output(Job job, java.nio.file.Path dir, List<String> namedOutputs) {
        job.setOutputKeyClass(NullWritable.class);
        job.setOutputValueClass(Text.class);
        LazyOutputFormat.setOutputFormatClass(job, TextOutputFormat.class);
        FileOutputFormat.setOutputPath(job, hadoopPath(dir));
        for (String name : namedOutputs) {
            MultipleOutputs.addNamedOutput(job, name, TextOutputFormat.class, NullWritable.class, Text.class);
        }
    }

    static Path hadoopPath(java.nio.file.Path path) {
        return new Path(path.toUri());
    }
}
