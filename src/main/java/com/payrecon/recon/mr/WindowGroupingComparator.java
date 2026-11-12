package com.payrecon.recon.mr;

import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.io.WritableComparator;

/** Records with the same bank+amount go into ONE reduce() call, even though their full keys differ. */
public class WindowGroupingComparator extends WritableComparator {

    public WindowGroupingComparator() {
        super(WindowKey.class, true);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public int compare(WritableComparable a, WritableComparable b) {
        return ((WindowKey) a).group().compareTo(((WindowKey) b).group());
    }
}
