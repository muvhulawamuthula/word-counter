package com.wordcount.benchmark;

import com.wordcount.counter.CompletableFutureCounter;
import com.wordcount.counter.ForkJoinCounter;
import com.wordcount.counter.ParallelStreamCounter;
import com.wordcount.counter.SequentialCounter;
import com.wordcount.model.WordCountResult;
import com.wordcount.util.FileScanner;
import com.wordcount.util.TestFileGenerator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class BenchmarkRunner {


    private static final int RUNS = 5;

    public static void main(String[] args) throws Exception {
        Path testDir = Path.of("test-files");

        if (!testDir.toFile().exists() ||
                testDir.toFile().listFiles() == null ||
                testDir.toFile().listFiles().length == 0) {
            TestFileGenerator.generate(testDir, 200, 10_000);
        }

        List<Path> files = FileScanner.scan(testDir);
        long totalBytes  = FileScanner.totalBytes(files);

        System.out.println("=== Word Counter Benchmark ===");
        System.out.println("Files  : " + files.size());
        System.out.printf("Size   : %.2f MB%n", totalBytes / 1024.0 / 1024.0);
        System.out.println("Cores  : " + Runtime.getRuntime().availableProcessors());
        System.out.println("Runs   : " + RUNS + " (averaged)");
        System.out.println();


        System.out.println("Warming up JIT...");
        for (int i = 0; i < 3; i++) {
            new SequentialCounter().count(files, totalBytes);
            new ForkJoinCounter().count(files, totalBytes);
            new CompletableFutureCounter().count(files, totalBytes);
            new ParallelStreamCounter().count(files, totalBytes);
        }
        System.out.println("Done.\n");


        long seqAvg = average(files, totalBytes, "Sequential",
                () -> new SequentialCounter().count(files, totalBytes));

        long fjAvg  = average(files, totalBytes, "Fork/Join",
                () -> new ForkJoinCounter().count(files, totalBytes));

        long cfAvg  = average(files, totalBytes, "CompletableFuture",
                () -> new CompletableFutureCounter().count(files, totalBytes));

        long psAvg  = average(files, totalBytes, "Parallel Streams",
                () -> new ParallelStreamCounter().count(files, totalBytes));


        System.out.println("\n=== Final Results (averaged over " + RUNS + " runs) ===\n");
        System.out.println("Strategy               Avg Time    Speedup");
        System.out.println("─".repeat(45));

        printRow("Sequential",        seqAvg, totalBytes, seqAvg);
        printRow("Fork/Join",         fjAvg,  totalBytes, seqAvg);
        printRow("CompletableFuture", cfAvg,  totalBytes, seqAvg);
        printRow("Parallel Streams",  psAvg,  totalBytes, seqAvg);


        System.out.println("\n=== Fork/Join Threshold Sensitivity ===\n");
        System.out.println("Threshold   Avg Time    Speedup vs Sequential");
        System.out.println("─".repeat(45));

        for (int threshold : new int[]{1, 5, 10, 25, 50, 100}) {
            final int t = threshold;
            long avg = averageRaw(RUNS,
                    () -> new ForkJoinCounter(t).count(files, totalBytes));
            double speedup = (double) seqAvg / avg;
            System.out.printf("%-12d  %6dms    %.2f×%n", t, avg, speedup);
        }
    }


    static long average(List<Path> files, long totalBytes,
                        String name, CounterTask task) throws Exception {
        System.out.println("Running: " + name);
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < RUNS; i++) {
            WordCountResult r = task.run();
            System.out.printf("  Run %d: %dms%n", i + 1, r.elapsedMs);
            times.add(r.elapsedMs);
        }
        long avg = (long) times.stream().mapToLong(Long::longValue).average().orElse(0);
        System.out.printf("  Avg  : %dms%n%n", avg);
        return avg;
    }

    static long averageRaw(int runs, CounterTask task) throws Exception {
        long total = 0;
        for (int i = 0; i < runs; i++) total += task.run().elapsedMs;
        return total / runs;
    }

    static void printRow(String name, long avgMs, long totalBytes, long baselineMs) {
        double mbPerSec = (totalBytes / 1024.0 / 1024.0) / (avgMs / 1000.0);
        double speedup  = (double) baselineMs / avgMs;
        System.out.printf("%-22s %6dms   %5.2f×%n", name, avgMs, speedup);
    }

    @FunctionalInterface
    interface CounterTask {
        WordCountResult run() throws Exception;
    }
}
