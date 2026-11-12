package com.payrecon.common;

import java.time.LocalDate;

/**
 * Tab-separated values: the record format passed between MapReduce jobs and read by Hive.
 * A missing value is written as \N, which is how Hive text tables represent NULL.
 */
public final class Tsv {

    public static final String NULL = "\\N";

    private Tsv() {
    }

    public static String join(Object... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append('\t');
            }
            Object field = fields[i];
            sb.append(field == null ? NULL : clean(field.toString()));
        }
        return sb.toString();
    }

    public static String[] split(String line) {
        return line.split("\t", -1);
    }

    public static String str(String field) {
        return (field == null || NULL.equals(field)) ? null : field;
    }

    public static Long lng(String field) {
        return (field == null || NULL.equals(field) || field.isEmpty()) ? null : Long.valueOf(field);
    }

    public static LocalDate date(String field) {
        return (field == null || NULL.equals(field) || field.isEmpty()) ? null : LocalDate.parse(field);
    }

    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
