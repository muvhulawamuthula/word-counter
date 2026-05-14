package com.wordcount.counter;

import com.wordcount.model.WordCountResult;
import com.wordcount.util.ResultMerger;
import com.wordcount.util.WordTokenizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SequentialCounter {

    public WordCountResult count(List<Path> files, long totalBytes) {
        long start = System.currentTimeMillis();

        Map<String, Long> combined = new HashMap<>();

        for (Path file : files) {
            try {
                String content = Files.readString(file);
                Map<String, Long> fileCounts = WordTokenizer.tokenize(content);
                ResultMerger.merge(combined, fileCounts);
            } catch (IOException e) {
                System.err.println("Failed to read: " + file + " — " + e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        return new WordCountResult("Sequential", combined, totalBytes, elapsed);
    }
}
