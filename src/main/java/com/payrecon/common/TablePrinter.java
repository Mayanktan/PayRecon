package com.payrecon.common;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Prints simple aligned tables in the terminal. */
public final class TablePrinter {

    private final List<String> headers;
    private final List<List<String>> rows = new ArrayList<>();
    private final Set<Integer> rightAligned = new HashSet<>();

    public TablePrinter(String... headers) {
        this.headers = Arrays.asList(headers);
    }

    public TablePrinter alignRight(int... columns) {
        for (int c : columns) {
            rightAligned.add(c);
        }
        return this;
    }

    public void add(Object... cells) {
        List<String> row = new ArrayList<>();
        for (Object cell : cells) {
            row.add(cell == null ? "" : cell.toString());
        }
        rows.add(row);
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    public void print(PrintStream out) {
        int[] widths = new int[headers.size()];
        for (int i = 0; i < headers.size(); i++) {
            widths[i] = headers.get(i).length();
        }
        for (List<String> row : rows) {
            for (int i = 0; i < row.size() && i < widths.length; i++) {
                widths[i] = Math.max(widths[i], row.get(i).length());
            }
        }
        out.println(format(headers, widths));
        StringBuilder rule = new StringBuilder();
        for (int i = 0; i < widths.length; i++) {
            if (i > 0) {
                rule.append("  ");
            }
            rule.append("-".repeat(widths[i]));
        }
        out.println(rule);
        for (List<String> row : rows) {
            out.println(format(row, widths));
        }
    }

    private String format(List<String> cells, int[] widths) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < widths.length; i++) {
            if (i > 0) {
                sb.append("  ");
            }
            String cell = i < cells.size() ? cells.get(i) : "";
            String pad = " ".repeat(Math.max(0, widths[i] - cell.length()));
            sb.append(rightAligned.contains(i) ? pad + cell : cell + pad);
        }
        return sb.toString().stripTrailing();
    }
}
