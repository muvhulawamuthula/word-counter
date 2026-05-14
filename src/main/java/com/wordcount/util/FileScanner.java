package com.wordcount.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class FileScanner {

    public static List<Path> scan(Path directory) throws IOException {
        try (var stream = Files.walk(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".txt"))
                    .toList();
        }
    }

    public static long totalBytes(List<Path> files) {
        return files.stream().mapToLong(p -> {
            try { return Files.size(p); }
            catch (IOException e) { return 0; }
        }).sum();
    }
}
