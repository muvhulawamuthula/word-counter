package com.wordcount.model;

import java.util.Map;

public class WordCountResult {

    public final String strategyName;
    public final Map<String, Long> wordCounts;
    public final long totalWords;
    public final long elapsedMs;
    public final long totalBytes;

    public WordCountResult(String strategyName, Map<String, Long> wordCounts,
                           long totalBytes, long elapsedMs) {
        this.strategyName = strategyName;
        this.wordCounts   = wordCounts;
        this.totalWords   = wordCounts.values().stream().mapToLong(Long::longValue).sum();
        this.elapsedMs    = elapsedMs;
        this.totalBytes   = totalBytes;
    }

    public double wordsPerSecond() {
        return elapsedMs == 0 ? 0 : (totalWords / (elapsedMs / 1000.0));
    }

    public double mbPerSecond() {
        return elapsedMs == 0 ? 0 : ((totalBytes / 1024.0 / 1024.0) / (elapsedMs / 1000.0));
    }
}
