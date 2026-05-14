package com.wordcount.counter;

import com.wordcount.model.WordCountResult;
import com.wordcount.util.WordTokenizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ParallelStreamCounter {

    public WordCountResult count(List<Path> files, long totalBytes) {
        long start = System.currentTimeMillis();

        Map<String, Long> combined = files.parallelStream()
                .map(this::countFile)           // count each file in parallel
                .flatMap(m -> m.entrySet().stream())  // flatten all entries
                .collect(Collectors.toConcurrentMap(  // merge into one map
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        Long::sum
                ));

        long elapsed = System.currentTimeMillis() - start;
        return new WordCountResult("Parallel Streams", combined, totalBytes, elapsed);
    }

    private Map<String, Long> countFile(Path file) {
        try {
            String content = Files.readString(file);
            return WordTokenizer.tokenize(content);
        } catch (IOException e) {
            System.err.println("Failed to read: " + file);
            return Map.of();
        }
    }
}
