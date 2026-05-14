package com.wordcount;

import com.wordcount.counter.CompletableFutureCounter;
import com.wordcount.counter.ForkJoinCounter;
import com.wordcount.counter.ParallelStreamCounter;
import com.wordcount.counter.SequentialCounter;
import com.wordcount.model.WordCountResult;
import com.wordcount.util.FileScanner;
import com.wordcount.util.TestFileGenerator;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class Main {

    public static void main(String[] args) throws Exception {
        Path testDir = Path.of("test-files");

        if (!testDir.toFile().exists() ||
                testDir.toFile().listFiles() == null ||
                testDir.toFile().listFiles().length == 0) {
            TestFileGenerator.generate(testDir, 500, 50_000);
        }

        List<Path> files = FileScanner.scan(testDir);
        long totalBytes  = FileScanner.totalBytes(files);

        System.out.println("Files  : " + files.size());
        System.out.printf("Size   : %.2f MB%n", totalBytes / 1024.0 / 1024.0);
        System.out.println("Cores  : " + Runtime.getRuntime().availableProcessors());
        System.out.println();


        System.out.println("Warming up JVM...");
        new SequentialCounter().count(files, totalBytes);
        new ForkJoinCounter().count(files, totalBytes);
        new CompletableFutureCounter().count(files, totalBytes);
        new ParallelStreamCounter().count(files, totalBytes);
        System.out.println("Done.\n");


        WordCountResult sequential  = new SequentialCounter().count(files, totalBytes);
        WordCountResult forkJoin    = new ForkJoinCounter().count(files, totalBytes);
        WordCountResult cfuture     = new CompletableFutureCounter().count(files, totalBytes);
        WordCountResult pstreams    = new ParallelStreamCounter().count(files, totalBytes);

        long baseline = sequential.elapsedMs;


        System.out.println("Strategy               Time       Throughput   Speedup");
        System.out.println("─".repeat(57));
        printResult(sequential, baseline);
        printResult(forkJoin,   baseline);
        printResult(cfuture,    baseline);
        printResult(pstreams,   baseline);


        System.out.println();
        verifyConsistency(sequential, forkJoin, cfuture, pstreams);


        printTopWords(forkJoin.wordCounts, 10);
    }

    static void printResult(WordCountResult r, long baselineMs) {
        double speedup = (double) baselineMs / r.elapsedMs;
        System.out.printf("%-22s %6dms   %6.1f MB/s   %5.2f×%n",
                r.strategyName, r.elapsedMs, r.mbPerSecond(), speedup);
    }

    static void verifyConsistency(WordCountResult... results) {
        long baseline = results[0].totalWords;
        boolean allMatch = true;
        for (WordCountResult r : results) {
            if (r.totalWords != baseline) {
                System.out.println("MISMATCH: " + r.strategyName +
                        " got " + r.totalWords + " vs " + baseline);
                allMatch = false;
            }
        }
        if (allMatch) {
            System.out.println("✓ All strategies agree: " +
                    String.format("%,d", baseline) + " total words");
        }
    }

    static void printTopWords(Map<String, Long> counts, int n) {
        System.out.println("\nTop " + n + " words:");
        counts.entrySet().stream()
                .sorted(Comparator.comparingLong(
                        Map.Entry<String, Long>::getValue).reversed())
                .limit(n)
                .forEach(e -> System.out.printf("  %-15s → %,d%n",
                        e.getKey(), e.getValue()));
        System.out.println();
    }
}
