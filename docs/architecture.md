# Architecture

How `word-counter` moves bytes from disk into a shared frequency map, and how the four strategies differ without changing tokenization or merge semantics.

## Goals

1. **Same problem, four schedulers** — isolate concurrency style as the variable.
2. **Shared utilities** — one scanner, tokenizer, and merger so result drift means a concurrency bug, not a parsing difference.
3. **Observable runs** — wall-clock timing, bytes → MB/s, speedup vs sequential, optional threshold sweep.

## High-level flow

```text
                    ┌─────────────────────┐
                    │  test-files/*.txt   │
                    │  (or generated)     │
                    └─────────┬───────────┘
                              │ FileScanner.scan / totalBytes
                              ▼
                    ┌─────────────────────┐
                    │   List<Path> files  │
                    │   long totalBytes   │
                    └─────────┬───────────┘
                              │
          ┌───────────────────┼───────────────────┐
          ▼                   ▼                   ▼
   SequentialCounter   ForkJoinCounter   CompletableFutureCounter
          │                   │                   │
          │                   │                   │
          └─────────► ParallelStreamCounter ◄─────┘
                              │
                              │ each: read → tokenize → merge
                              ▼
                    ┌─────────────────────┐
                    │  WordCountResult    │
                    │  name, map, times   │
                    └─────────┬───────────┘
                              │
                    Main: table + verify + top-N
                    BenchmarkRunner: averages + thresholds
```

## Components

### Entry points

| Class | Role |
|---|---|
| `com.wordcount.Main` | Demo comparison: warm-up once, one timed pass each, consistency check, top 10 words. Default corpus **500 × 50_000** if `test-files/` empty. |
| `com.wordcount.benchmark.BenchmarkRunner` | Measurement harness: 3× warm-up all strategies, **5** timed runs averaged, then Fork/Join thresholds `{1,5,10,25,50,100}`. Default corpus **200 × 10_000** if empty. |

Both refuse to regenerate if `test-files/` already has files — delete the directory to pick up new sizes.

### Model

`WordCountResult` is an immutable-ish snapshot:

- `strategyName`, `wordCounts`, `totalWords` (sum of values), `elapsedMs`, `totalBytes`
- Helpers: `wordsPerSecond()`, `mbPerSecond()` (guard `elapsedMs == 0`)

Timing is `System.currentTimeMillis()` around the whole count call (includes I/O + tokenize + merge for that strategy).

### Utilities

| Type | Responsibility |
|---|---|
| `FileScanner` | `Files.walk` → regular files ending in `.txt`; `totalBytes` via `Files.size` (0 on error). |
| `WordTokenizer` | Lower-case; `split("[^a-z']+")`; keep tokens with `length > 1`; `Map.merge` counts. |
| `ResultMerger` | Mutates `base` with `base.merge(word, count, Long::sum)`; returns `base`. |
| `TestFileGenerator` | Creates `file-0000.txt`… with `Random(42)` over a fixed 40-word vocabulary; line breaks every 12 words. |

### Counters (strategies)

All expose essentially:

```text
WordCountResult count(List<Path> files, long totalBytes)
```

Per-file work is always: `Files.readString` → `WordTokenizer.tokenize`. Differences are **scheduling and how maps combine**.

#### SequentialCounter

- Single `HashMap` accumulated in a `for` loop.
- Errors: log and continue (skip file).

#### ForkJoinCounter

- Outer: `ForkJoinPool.commonPool().invoke(new WordCountTask(files, threshold))`.
- Default threshold **10** (`ForkJoinCounter()` / `ForkJoinCounter(int)`).
- `WordCountTask extends RecursiveTask<Map<String, Long>>`:
  - If `files.size() <= threshold` → sequential leaf count into a local map.
  - Else mid-split; **fork left, compute right, join left**, then `ResultMerger.merge(left, right)`.
- Leaf I/O errors: log and skip that file.

#### CompletableFutureCounter

- `Executors.newVirtualThreadPerTaskExecutor()`.
- One future per file; `allOf(...).join()`.
- Caller builds a fresh `HashMap` and merges each future’s map **sequentially** after completion.
- `executor.shutdown()` after merge.
- Failed reads return empty `HashMap`.

#### ParallelStreamCounter

- `files.parallelStream().map(countFile).flatMap(entrySet).collect(toConcurrentMap(key, value, Long::sum))`.
- Failed reads return `Map.of()`.
- Parallelism level = common pool (typically `availableProcessors() - 1` for worker count semantics; do not assume equality with Fork/Join task tree depth).

## Concurrency & shared state

| Strategy | Shared mutable state during count? |
|---|---|
| Sequential | Single-threaded map only |
| Fork/Join | Maps owned per task; merged on join path (no concurrent map writes across tasks) |
| CompletableFuture | Per-file maps; single-threaded merge after join |
| Parallel Streams | `ConcurrentHashMap` via `toConcurrentMap` with `Long::sum` combiner |

That design keeps Fork/Join and CF free of lock contention on the hot map; streams pay for concurrent map updates instead.

## Correctness contract

`Main.verifyConsistency` compares **`totalWords` only** (sum of frequencies), not full map equality. Sufficient to catch most lost updates; a bug that drops one word and invents another with the same total would not be caught. For stronger checks, compare sorted entry streams or checksums of the maps.

## Build & packaging

- Maven, Java **21** compiler release.
- `exec-maven-plugin` for `Main` / `BenchmarkRunner`.
- `maven-shade-plugin` → runnable JAR with `Main-Class: com.wordcount.Main`.

No test sources in-tree at the time of this doc; validation is the runtime consistency check plus manual benchmark inspection.

## Extension points

- Swap `Files.readString` for NIO channels / mmap without touching strategy classes (extract a `FileContentReader`).
- Inject a custom `ForkJoinPool` instead of `commonPool()` for isolation from other parallel streams.
- Parallelize the CF merge (e.g. tree-reduce futures) if merge becomes the bottleneck on huge vocabularies — current vocab is tiny (~40 types from the generator), so merge is cheap.