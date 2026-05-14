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
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

public class ForkJoinCounter {

    private final int threshold;

    public ForkJoinCounter() {
        this(10);
    }

    public ForkJoinCounter(int threshold) {
        this.threshold = threshold;
    }

    public WordCountResult count(List<Path> files, long totalBytes) {
        long start = System.currentTimeMillis();

        ForkJoinPool pool = ForkJoinPool.commonPool();
        Map<String, Long> combined = pool.invoke(new WordCountTask(files, threshold));

        long elapsed = System.currentTimeMillis() - start;
        return new WordCountResult("Fork/Join", combined, totalBytes, elapsed);
    }

    static class WordCountTask extends RecursiveTask<Map<String, Long>> {

        private final List<Path> files;
        private final int threshold;

        WordCountTask(List<Path> files, int threshold) {
            this.files     = files;
            this.threshold = threshold;
        }

        @Override
        protected Map<String, Long> compute() {
            if (files.size() <= threshold) {
                return countFiles(files);
            }

            int mid = files.size() / 2;
            WordCountTask left  = new WordCountTask(files.subList(0, mid), threshold);
            WordCountTask right = new WordCountTask(files.subList(mid, files.size()), threshold);

            left.fork();
            Map<String, Long> rightResult = right.compute();
            Map<String, Long> leftResult  = left.join();

            return ResultMerger.merge(leftResult, rightResult);
        }

        private Map<String, Long> countFiles(List<Path> files) {
            Map<String, Long> counts = new HashMap<>();
            for (Path file : files) {
                try {
                    String content = Files.readString(file);
                    ResultMerger.merge(counts, WordTokenizer.tokenize(content));
                } catch (IOException e) {
                    System.err.println("Failed to read: " + file);
                }
            }
            return counts;
        }
    }
}