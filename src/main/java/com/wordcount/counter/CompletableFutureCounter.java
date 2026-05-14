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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CompletableFutureCounter {

    public WordCountResult count(List<Path> files, long totalBytes) {
        long start = System.currentTimeMillis();


        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();


        List<CompletableFuture<Map<String, Long>>> futures = files.stream()
                .map(file -> CompletableFuture.supplyAsync(
                        () -> countFile(file), executor))
                .toList();


        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();


        Map<String, Long> combined = new HashMap<>();
        for (CompletableFuture<Map<String, Long>> future : futures) {
            ResultMerger.merge(combined, future.join());
        }

        executor.shutdown();

        long elapsed = System.currentTimeMillis() - start;
        return new WordCountResult("CompletableFuture", combined, totalBytes, elapsed);
    }

    private Map<String, Long> countFile(Path file) {
        try {
            String content = Files.readString(file);
            return WordTokenizer.tokenize(content);
        } catch (IOException e) {
            System.err.println("Failed to read: " + file);
            return new HashMap<>();
        }
    }
}
