package com.payrecon.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Small file helpers. */
public final class FileUtil {

    private FileUtil() {
    }

    public static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            List<Path> all = new ArrayList<>();
            walk.forEach(all::add);
            all.sort(Comparator.reverseOrder()); // children before parents
            for (Path p : all) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** Hadoop "part" files in a folder (skips hidden .crc files and _SUCCESS markers). Empty if no folder. */
    public static List<Path> partFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return !name.startsWith(".") && !name.startsWith("_");
                    })
                    .sorted()
                    .toList();
        }
    }

    /** Copies the part files of {@code from} into {@code to}, adding a prefix so files from many sources never clash. */
    public static int copyParts(Path from, Path to, String prefix) throws IOException {
        int copied = 0;
        for (Path file : partFiles(from)) {
            Files.createDirectories(to);
            Files.copy(file, to.resolve(prefix + file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            copied++;
        }
        return copied;
    }
}
