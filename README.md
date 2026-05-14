# Parallel File Word Counter

A command-line tool that counts word frequencies across a large collection of files using four different parallelism strategies — then benchmarks them head to head.

Built to deeply understand Java concurrency primitives: Fork/Join, CompletableFuture, parallel streams, and why the right strategy depends on whether your bottleneck is CPU or I/O.

---

## What it does

- Scans a directory of `.txt` files
- Counts every word and its frequency across all files
- Runs four strategies on the same input and compares throughput
- Runs a threshold sensitivity analysis on Fork/Join to find the optimal split size
- Verifies all strategies produce identical results

---

## Strategies compared

| Strategy | Approach | Best for |
|---|---|---|
| Sequential | Single thread, one file at a time | Baseline measurement |
| Fork/Join | Recursive split with work stealing | CPU-bound, uneven workloads |
| CompletableFuture | One async task per file, virtual threads | I/O-bound, many small files |
| Parallel Streams | `parallelStream()` + `toConcurrentMap` | Simple pipelines, minimal code |

---

## Project structure

```
src/main/java/com/wordcount/
├── Main.java                          # Entry point — runs all four strategies
├── counter/
│   ├── SequentialCounter.java         # Single-threaded baseline
│   ├── ForkJoinCounter.java           # RecursiveTask with configurable threshold
│   ├── CompletableFutureCounter.java  # Async per-file with virtual thread executor
│   └── ParallelStreamCounter.java     # parallelStream + toConcurrentMap
├── model/
│   └── WordCountResult.java           # Holds counts, timing, throughput stats
├── util/
│   ├── FileScanner.java               # Walks directory, returns list of paths
│   ├── WordTokenizer.java             # Splits text into normalised word counts
│   ├── ResultMerger.java              # Merges Map<String,Long> by summing counts
│   └── TestFileGenerator.java         # Generates reproducible test files
└── benchmark/
    └── BenchmarkRunner.java           # Averaged runs + threshold sensitivity analysis
```

---

## Prerequisites

- Java 21+
- Maven 3.8+

---

## Getting started

```bash
git clone https://github.com/yourusername/word-counter.git
cd word-counter
mvn package -q
```

Test files are generated automatically on first run.

---

## Running

### All four strategies — single comparison run

```bash
mvn compile exec:java -Dexec.mainClass="com.wordcount.Main"
```

```
Files  : 200
Size   : 1.91 MB
Cores  : 4

Strategy               Time       Throughput   Speedup
─────────────────────────────────────────────────────────
Sequential              186ms     10.2 MB/s    1.00×
Fork/Join                54ms     35.4 MB/s    3.44×
CompletableFuture        61ms     31.3 MB/s    3.05×
Parallel Streams         58ms     32.9 MB/s    3.21×

✓ All strategies agree: 2,000,000 total words
```

### Full benchmark — averaged runs + threshold sensitivity

```bash
mvn compile exec:java -Dexec.mainClass="com.wordcount.benchmark.BenchmarkRunner"
```

```
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

### Scale up the input

To generate a larger dataset, edit `Main.java`:

```java
TestFileGenerator.generate(testDir, 500, 50_000); // 500 files × 50k words = 25M words
```

Then delete `test-files/` and re-run. Larger input reveals I/O vs CPU bottlenecks more clearly.

---

## How each strategy works

### Sequential (baseline)

Processes files one at a time in a simple loop. No parallelism. Read file → tokenize → merge into combined map → repeat.

```java
for (Path file : files) {
    String content = Files.readString(file);
    ResultMerger.merge(combined, WordTokenizer.tokenize(content));
}
```

Fast enough for small inputs. The number to beat.

---

### Fork/Join

Uses `ForkJoinPool` with `RecursiveTask` to recursively split the file list until each subtask is small enough to count directly. Results bubble back up the tree, merging at each level.

```
Task(0-199)
├── Task(0-99)
│   ├── Task(0-49)  → count → Map
│   └── Task(50-99) → count → Map → merge
└── Task(100-199)
    ├── Task(100-149) → count → Map
    └── Task(150-199) → count → Map → merge
                                       ↓
                                  final merge
```

The key implementation detail — always fork one half and compute the other in the current thread:

```java
leftTask.fork();                       // runs on another thread
Map<String, Long> right = rightTask.compute(); // current thread stays busy
Map<String, Long> left  = leftTask.join();     // wait for forked task
return ResultMerger.merge(left, right);
```

Forking both halves wastes the current thread while it waits. Computing one half directly keeps every thread productive.

**Work stealing:** idle threads steal tasks from the back of busy threads' deques. This self-balances when files are uneven in size — no manual load balancing needed.

**Threshold:** the split stops when a task has ≤ threshold files and counts them directly. Too small → task creation overhead dominates. Too large → cores sit idle. The benchmark finds the sweet spot empirically.

---

### CompletableFuture

Submits one async task per file to a virtual thread executor. All tasks run concurrently — no recursive splitting, no work stealing. Results are merged sequentially after all futures complete.

```java
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

List<CompletableFuture<Map<String, Long>>> futures = files.stream()
        .map(file -> CompletableFuture.supplyAsync(() -> countFile(file), executor))
        .toList();

CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

// merge all results
futures.forEach(f -> ResultMerger.merge(combined, f.join()));
```

Virtual threads park cheaply during `Files.readString()` I/O — no OS thread sits idle while waiting for disk. This makes CompletableFuture + virtual threads particularly effective when I/O latency dominates.

---

### Parallel Streams

The most concise implementation. `parallelStream()` partitions the file list across the common `ForkJoinPool` automatically. `toConcurrentMap` handles thread-safe merging via `Long::sum`.

```java
Map<String, Long> combined = files.parallelStream()
        .map(this::countFile)
        .flatMap(m -> m.entrySet().stream())
        .collect(Collectors.toConcurrentMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                Long::sum
        ));
```

**Tradeoffs vs Fork/Join:**
- No control over thread pool or parallelism level
- Error handling inside streams is awkward
- Cannot tune the split strategy
- Sufficient for most simple pipelines — overkill to reach for Fork/Join unless you need the control

---

## Key concepts

### Fork/Join vs thread pool

A fixed thread pool assigns each task to a thread and that thread works on it until done. If some tasks are slow (large files), other threads finish early and sit idle.

`ForkJoinPool` uses work stealing — idle threads steal pending tasks from the back of busy threads' queues. Uneven workloads self-balance. This is why Fork/Join consistently outperforms a fixed pool for recursive divide-and-conquer problems.

### Amdahl's Law in practice

Not all of a program can be parallelised. The final merge at the top of the Fork/Join tree is sequential. If 20% of total time is sequential, no amount of parallelism can produce more than a 5× speedup — regardless of core count.

This is why the threshold sensitivity table matters: splitting too finely doesn't improve past a point because the sequential merge overhead becomes the bottleneck.

### I/O vs CPU bottleneck

Word counting has two phases:
- **Read** — `Files.readString()` — I/O bound
- **Tokenize + merge** — CPU bound

On an SSD with fast sequential reads, reading is fast and CPU dominates — Fork/Join's CPU efficiency shines. On a slow disk or network filesystem, I/O dominates — CompletableFuture with virtual threads wins because virtual threads park cheaply during waits.

Always profile and measure. Never assume which bottleneck you have.

### Result correctness

Parallel code is only useful if it produces the same result as sequential code. The consistency check at the end of each run verifies all four strategies count exactly the same number of words:

```
✓ All strategies agree: 2,000,000 total words
```

Any merge bug — race condition, lost update, wrong accumulator — shows up here immediately.

---

## Why this matters

Most developers reach for `parallelStream()` and call it done. This project builds the understanding to answer questions that most senior candidates cannot:

- Why does Fork/Join outperform a fixed thread pool on uneven workloads?
- What is work stealing and why does it matter?
- When does CompletableFuture beat Fork/Join?
- Why does a threshold of 1 (maximum splits) perform worse than a threshold of 10?
- What does Amdahl's Law predict about the maximum speedup on your hardware?

The benchmark output is evidence you can walk through in an interview — not just a claim that you understand concurrency.

---

## Stretch goals

- [ ] Add a stop-word filter (`the`, `and`, `of`) and observe its effect on top-word rankings
- [ ] Process `.gz` compressed files via `GZIPInputStream` — measure decompression overhead
- [ ] Replace `Files.readString()` with `FileChannel.map()` (memory-mapped I/O) and benchmark the difference
- [ ] Process a single large file by splitting it into byte-range chunks — handle words split at chunk boundaries
- [ ] Add a progress bar using `AtomicInteger` — thread-safe counter without locks
- [ ] Add JFR recording and compare CPU utilisation profiles between strategies
- [ ] Try `ForkJoinPool` with explicit parallelism levels (`new ForkJoinPool(n)`) vs common pool

---

## References

- [Java SE 21 — ForkJoinPool](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ForkJoinPool.html)
- [JEP 444 — Virtual Threads](https://openjdk.org/jeps/444)
- [Java Concurrency in Practice — Goetz et al.](https://jcip.net)
- [Amdahl's Law — Wikipedia](https://en.wikipedia.org/wiki/Amdahl%27s_law)

---

## License

MIT
