package com.payrecon.recon.mr;

import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Partitioner;

/** Sends every record of one bank+amount group to the same reducer (the date is ignored on purpose). */
public class WindowPartitioner extends Partitioner<WindowKey, Text> {

    @Override
    public int getPartition(WindowKey key, Text value, int numPartitions) {
        return (key.group().hashCode() & Integer.MAX_VALUE) % numPartitions;
    }
}
