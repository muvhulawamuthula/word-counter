# Parallel File Word Counter

Java 21 CLI that counts word frequencies across many `.txt` files using four concurrency strategies, then compares them on the same input.

Built to explore when **Fork/Join**, **CompletableFuture + virtual threads**, and **parallel streams** win — and why the bottleneck (CPU vs I/O) matters more than the API you pick.

---

## Features

- Directory scan of `.txt` files (`FileScanner`)
- Per-file tokenize → global frequency map
- Four strategies on identical input, with throughput and speedup vs sequential
- Warm-up pass before measured runs (`Main`) / JIT warm-up + averaged runs (`BenchmarkRunner`)
- Fork/Join **threshold sensitivity** sweep (`1, 5, 10, 25, 50, 100`)
- Cross-strategy **total-word consistency** check
- Reproducible synthetic corpus (`TestFileGenerator`, `Random(42)`)

---

## Strategies

| Strategy | Implementation | Best when |
|---|---|---|
| **Sequential** | Single-threaded loop | Baseline |
| **Fork/Join** | `RecursiveTask` on `ForkJoinPool.commonPool()`, default threshold `10` | CPU-bound / uneven file sizes (work stealing) |
| **CompletableFuture** | One `supplyAsync` per file on `newVirtualThreadPerTaskExecutor()` | I/O-bound / many files (cheap parking on disk waits) |
| **Parallel Streams** | `parallelStream()` + `Collectors.toConcurrentMap(..., Long::sum)` | Simple pipelines, minimal code |

---

## Requirements

- **Java 21+** (virtual threads, language level in `pom.xml`)
- **Maven 3.8+**

---

## Quick start

```bash
git clone https://github.com/muvhulawamuthula/word-counter.git
cd word-counter
mvn -q package
```

Fat JAR (shade plugin) entry point: `com.wordcount.Main`.

```bash
java -jar target/word-counter-1.0-SNAPSHOT.jar
# or
mvn -q compile exec:java -Dexec.mainClass="com.wordcount.Main"
```

On first run, if `test-files/` is missing or empty, **Main** generates:

```text
500 files × 50_000 words  →  25,000,000 tokens before filtering
```

---

## Running

### Single comparison (`Main`)

```bash
mvn -q compile exec:java -Dexec.mainClass="com.wordcount.Main"
```

What it does:

1. Ensures `test-files/` exists (generates **500 × 50k** if needed)
2. Warm-up: one run of each strategy (discarded)
3. Timed run of Sequential → Fork/Join → CompletableFuture → Parallel Streams
4. Prints time, MB/s, speedup vs sequential
5. Verifies all strategies report the same `totalWords`
6. Prints top 10 words from the Fork/Join result

Illustrative output shape (timings depend on machine; numbers below are from a smaller corpus — see [docs/benchmarks.md](docs/benchmarks.md)):

```text
Files  : 500
Size   : ~XX MB
Cores  : N

Warming up JVM...
Done.

Strategy               Time       Throughput   Speedup
─────────────────────────────────────────────────────────
Sequential              …ms       … MB/s       1.00×
Fork/Join               …ms       … MB/s       …
CompletableFuture       …ms       … MB/s       …
Parallel Streams        …ms       … MB/s       …

✓ All strategies agree: … total words

Top 10 words:
  …
```

### Averaged benchmark + threshold sweep (`BenchmarkRunner`)

```bash
mvn -q compile exec:java -Dexec.mainClass="com.wordcount.benchmark.BenchmarkRunner"
```

Defaults when generating data: **200 files × 10_000 words**, **5** timed runs after **3** warm-up iterations of each strategy, then Fork/Join thresholds `{1, 5, 10, 25, 50, 100}`.

Example numbers from a 4-core run on that smaller corpus (treat as illustrative; re-run locally):

```text
=== Final Results (averaged over 5 runs) ===

Strategy               Avg Time    Speedup
─────────────────────────────────────────────
Sequential              186ms      1.00×
Fork/Join                54ms      3.44×
CompletableFuture        61ms      3.05×
Parallel Streams         58ms      3.21×

=== Fork/Join Threshold Sensitivity ===

Threshold   Avg Time    Speedup vs Sequential
─────────────────────────────────────────────
1             98ms      1.90×
5             61ms      3.05×
10            54ms      3.44×
25            57ms      3.26×
50            72ms      2.58×
100          119ms      1.56×
```

On that run, **threshold 10** (the `ForkJoinCounter` default) was the sweet spot.

### Scale the corpus

Edit the generator call (or delete `test-files/` after changing it):

| Entry point | Default generation |
|---|---|
| `Main` | `TestFileGenerator.generate(testDir, 500, 50_000)` |
| `BenchmarkRunner` | `TestFileGenerator.generate(testDir, 200, 10_000)` |

Larger inputs make CPU vs I/O trade-offs clearer. Word list is fixed (40 CS-ish tokens); seed `42` keeps runs reproducible.

---

## Project layout

```text
src/main/java/com/wordcount/
├── Main.java                            # Comparison entry + warm-up + top words
├── counter/
│   ├── SequentialCounter.java           # Baseline
│   ├── ForkJoinCounter.java             # RecursiveTask; threshold (default 10)
│   ├── CompletableFutureCounter.java    # VT executor; merge after allOf
│   └── ParallelStreamCounter.java       # parallelStream + toConcurrentMap
├── model/
│   └── WordCountResult.java             # counts, elapsedMs, MB/s, words/s
├── util/
│   ├── FileScanner.java                 # walk → *.txt; totalBytes
│   ├── WordTokenizer.java               # lower-case; [^a-z']+; length > 1
│   ├── ResultMerger.java                # Map.merge(..., Long::sum)
│   └── TestFileGenerator.java           # seeded synthetic .txt files
└── benchmark/
    └── BenchmarkRunner.java             # averaged runs + threshold sweep
```

Further reading:

- [Architecture](docs/architecture.md) — data flow, pooling, merge semantics
- [Benchmarks](docs/benchmarks.md) — how to measure, interpret thresholds, caveats

---

## How each strategy works (code-accurate)

### Sequential

```java
for (Path file : files) {
    String content = Files.readString(file);
    ResultMerger.merge(combined, WordTokenizer.tokenize(content));
}
```

### Fork/Join

Splits the file list until `files.size() <= threshold`, then counts in the leaf. Prefer **fork left / compute right** so the current worker stays busy:

```java
left.fork();
Map<String, Long> rightResult = right.compute();
Map<String, Long> leftResult  = left.join();
return ResultMerger.merge(leftResult, rightResult);
```

Uses `ForkJoinPool.commonPool()` via `pool.invoke(...)`.

### CompletableFuture + virtual threads

```java
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
List<CompletableFuture<Map<String, Long>>> futures = files.stream()
        .map(file -> CompletableFuture.supplyAsync(() -> countFile(file), executor))
        .toList();
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
// sequential merge of completed maps
```

Virtual threads park during `Files.readString`; merge happens on the calling thread after `allOf`.

### Parallel streams

```java
files.parallelStream()
     .map(this::countFile)
     .flatMap(m -> m.entrySet().stream())
     .collect(Collectors.toConcurrentMap(
             Map.Entry::getKey, Map.Entry::getValue, Long::sum));
```

Shares the common Fork/Join pool; less tunable than the explicit `ForkJoinCounter`.

---

## Tokenization rules

`WordTokenizer`:

1. Lower-case the whole file
2. Split on `[^a-z']+` (letters and apostrophes kept)
3. Drop blanks and tokens with `length <= 1`

So `"CPU"` → `cpu`; single-letter tokens are ignored. That affects absolute totals vs raw generator word counts.

---

## Correctness

Parallelism is worthless if maps disagree. After timed runs, `Main` compares `totalWords` across all four `WordCountResult`s. A race or bad merge surfaces as a mismatch printout.

---

## Design notes (interview-ready)

- **Work stealing** helps when file sizes differ; idle workers steal from busy deques.
- **Threshold too small** → task overhead; **too large** → underused cores (see sensitivity table).
- **Amdahl**: top-level merges and sequential post-processing bound speedup.
- **I/O vs CPU**: SSD + heavy tokenize favors Fork/Join / streams; slow FS favors virtual-thread CF.
- Measure on your hardware — do not treat sample tables as absolute rankings.

---

## Stretch ideas

- [ ] Stop-word filter and re-rank top words  
- [ ] `.gz` via `GZIPInputStream` — measure inflate cost  
- [ ] `FileChannel.map` / memory-mapped reads  
- [ ] Custom `ForkJoinPool` size vs `commonPool()`  
- [ ] JMH microbenchmarks instead of wall-clock averages  

---

## License

No license file in-repo yet — add one before treating this as reusable open source.