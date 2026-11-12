package com.payrecon.recon.mr;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;

/**
 * Normal TextInputFormat may cut a big file into several splits for several mappers.
 * We need every bank line's PHYSICAL line number (it is part of the line id), and a mapper can only
 * count lines if it reads the file from the start. So: one file = one split = one mapper.
 */
public class NonSplittableTextInputFormat extends TextInputFormat {

    @Override
    protected boolean isSplitable(JobContext context, Path file) {
        return false;
    }
}
