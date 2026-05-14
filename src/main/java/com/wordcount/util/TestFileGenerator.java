package com.wordcount.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

public class TestFileGenerator {


    private static final String[] WORDS = {
            "the", "java", "thread", "memory", "heap", "stack", "class",
            "object", "method", "interface", "stream", "lambda", "collect",
            "parallel", "fork", "join", "future", "async", "pool", "task",
            "count", "word", "file", "read", "write", "process", "result",
            "merge", "split", "compute", "performance", "benchmark", "fast",
            "slow", "cpu", "core", "lock", "sync", "atomic", "concurrent"
    };

    public static void generate(Path directory, int fileCount,
                                int wordsPerFile) throws IOException {
        Files.createDirectories(directory);
        Random random = new Random(42);

        System.out.printf("Generating %d files × %d words in %s...%n",
                fileCount, wordsPerFile, directory);

        for (int i = 0; i < fileCount; i++) {
            Path file = directory.resolve("file-" + String.format("%04d", i) + ".txt");
            try (BufferedWriter writer = Files.newBufferedWriter(file)) {
                for (int w = 0; w < wordsPerFile; w++) {
                    writer.write(WORDS[random.nextInt(WORDS.length)]);
                    if (w < wordsPerFile - 1) {
                        writer.write(w % 12 == 11 ? "\n" : " ");
                    }
                }
            }
        }
        System.out.println("Done.");
    }
}
