package com.payrecon.recon;

import com.payrecon.bank.Bank;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Finds bank files on disk. Every file name carries its date: HDFC_STMT_20261002.csv, cardnet_settlement_2026-10-02.jsonl ... */
public final class BankFiles {

    private static final Pattern DATE = Pattern.compile("(\\d{4})-?(\\d{2})-?(\\d{2})");

    private BankFiles() {
    }

    public static LocalDate dateOf(String fileName) {
        Matcher m = DATE.matcher(fileName);
        if (!m.find()) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** All bank files dated on or before the cutoff: data/bank_files/<BANK>/<file>. */
    public static List<Path> upTo(Path bankRoot, LocalDate through) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Bank bank : Bank.values()) {
            Path dir = bankRoot.resolve(bank.name());
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> !p.getFileName().toString().startsWith("."))
                        .filter(p -> {
                            LocalDate d = dateOf(p.getFileName().toString());
                            return d != null && !d.isAfter(through);
                        })
                        .sorted()
                        .forEach(files::add);
            }
        }
        return files;
    }
}
