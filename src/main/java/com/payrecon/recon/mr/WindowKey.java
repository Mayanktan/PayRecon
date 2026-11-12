package com.payrecon.recon.mr;

import org.apache.hadoop.io.WritableComparable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Objects;

/**
 * Composite key for job 2's SECONDARY SORT.
 *   group = "bank|amount"  -> decides which reducer call gets the record (partitioner + grouping comparator)
 *   day, kind, id          -> only decide the ORDER of records inside that call
 * Result: each reduce() call receives all records of one bank+amount, already sorted by date,
 * without the reducer having to sort anything in memory.
 */
public class WindowKey implements WritableComparable<WindowKey> {

    public static final byte LEDGER = 0;
    public static final byte BANK = 1;

    private String group = "";
    private int day;
    private byte kind;
    private String id = "";

    public void set(String group, int day, byte kind, String id) {
        this.group = group;
        this.day = day;
        this.kind = kind;
        this.id = id;
    }

    public String group() {
        return group;
    }

    @Override
    public void write(DataOutput out) throws IOException {
        out.writeUTF(group);
        out.writeInt(day);
        out.writeByte(kind);
        out.writeUTF(id);
    }

    @Override
    public void readFields(DataInput in) throws IOException {
        group = in.readUTF();
        day = in.readInt();
        kind = in.readByte();
        id = in.readUTF();
    }

    @Override
    public int compareTo(WindowKey other) {
        int c = group.compareTo(other.group);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(day, other.day);
        if (c != 0) {
            return c;
        }
        c = Byte.compare(kind, other.kind);
        return c != 0 ? c : id.compareTo(other.id);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof WindowKey)) {
            return false;
        }
        WindowKey k = (WindowKey) o;
        return day == k.day && kind == k.kind && group.equals(k.group) && id.equals(k.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(group, day, kind, id);
    }

    @Override
    public String toString() {
        return group + "@" + day + "/" + kind + "/" + id;
    }
}
