# Benchmarks

How this repo measures strategies, what the checked-in sample numbers mean, and how to produce trustworthy results on your machine.

## What is measured

Wall-clock milliseconds around each strategy’s `count(...)` call:

- File reads (`Files.readString`)
- Tokenization
- Map merges / concurrent collects

Reported metrics:

| Metric | Definition |
|---|---|
| **Time** | `elapsedMs` on `WordCountResult` |
| **Throughput** | `(totalBytes / 1 MiB) / (elapsedMs / 1000)` → MB/s |
| **Speedup** | `sequentialElapsed / strategyElapsed` |

`BenchmarkRunner` averages **5** timed runs after warm-up; `Main` does **one** timed pass after a single warm-up of each strategy.

## Default corpora (important)

The two entry points do **not** use the same generator defaults:

| Entry point | Auto-generate if `test-files/` empty | Approx. raw tokens |
|---|---|---|
| `Main` | `500` files × `50_000` words | 25,000,000 |
| `BenchmarkRunner` | `200` files × `10_000` words | 2,000,000 |

If `test-files/` already exists from a previous `Main` run, `BenchmarkRunner` will **reuse** that larger corpus (and vice versa). Delete `test-files/` before comparing harnesses, or generate explicitly.

Tokenization drops length-1 tokens and normalizes case, so **totalWords ≠ fileCount × wordsPerFile**.

Generator details (`TestFileGenerator`):

- Seed: `Random(42)` — reproducible contents
- Vocabulary: 40 fixed English/CS tokens
- Layout: `file-0000.txt` … space-separated, newline every 12 words

## Running the harness

```bash
# Prefer a clean slate for the smaller default corpus
rm -rf test-files

mvn -q compile exec:java -Dexec.mainClass="com.wordcount.benchmark.BenchmarkRunner"
```

Harness steps:

1. Create corpus if needed (`200 × 10_000`)
2. Print file count, size MiB, core count, run count
3. **Warm-up:** 3 iterations × all four strategies (JIT / page cache)
4. **Timed:** 5 runs each; print per-run and average
5. **Threshold sweep:** Fork/Join with thresholds `1, 5, 10, 25, 50, 100` (raw average of 5, using sequential average as baseline)

For a one-shot comparison on the large default:

```bash
rm -rf test-files
mvn -q compile exec:java -Dexec.mainClass="com.wordcount.Main"
```

## Sample results (illustrative)

The following table appeared in earlier project docs for a **~200-file / ~1.91 MB / ~2M-word** style run on a **4-core** machine. It matches the *shape* of `BenchmarkRunner`’s default corpus, not a CI artifact. **Re-run locally; do not cite these as absolute winners.**

### Strategy averages (5 runs)

| Strategy | Avg time | Speedup vs sequential |
|---|---:|---:|
| Sequential | 186 ms | 1.00× |
| Fork/Join (threshold 10) | 54 ms | 3.44× |
| CompletableFuture (virtual threads) | 61 ms | 3.05× |
| Parallel Streams | 58 ms | 3.21× |

### Fork/Join threshold sensitivity

| Threshold | Avg time | Speedup vs sequential |
|---:|---:|---:|
| 1 | 98 ms | 1.90× |
| 5 | 61 ms | 3.05× |
| 10 | 54 ms | 3.44× |
| 25 | 57 ms | 3.26× |
| 50 | 72 ms | 2.58× |
| 100 | 119 ms | 1.56× |

Interpretation consistent with the code:

- **Threshold 1** over-splits → task / merge overhead.
- **Threshold 10** matches `ForkJoinCounter`’s default and won on this sample.
- **Large thresholds** leave parallelism on the table (few leaf tasks).

On other hardware or with the **500 × 50k** corpus, rankings between Fork/Join, CF, and streams often shuffle depending on disk and core count.

## Methodology tips

1. **Warm-up** — cold runs include JIT and OS cache fills; both entry points warm deliberately.
2. **Pin the corpus** — same `test-files/` when comparing commits.
3. **Quiet machine** — close other heavy processes; `commonPool()` is process-global.
4. **Report environment** — Java version (`java -version`), OS, core count (printed by the apps), disk type.
5. **Prefer `BenchmarkRunner` for claims** — averages beat single `Main` timings.
6. **Wall clock ≠ JMH** — this is integration timing, not a microbenchmark framework. For publication-grade numbers, add JMH.

## Reading the trade-offs

```text
CPU-heavy tokenize + fast local SSD
  → Fork/Join / parallel streams often close; threshold tuning matters

Many small files + high I/O wait
  → Virtual-thread CompletableFuture can pull ahead (park during read)

Tiny vocabulary (this generator)
  → Merge cost is low; differences are mostly schedule + I/O overlap

Huge real-world vocabularies
  → Concurrent map updates (streams) or deep merge trees may dominate
```

## Changing experimental knobs

| Knob | Where |
|---|---|
| File count / words per file | `TestFileGenerator.generate(...)` in `Main` or `BenchmarkRunner` |
| Timed run count | `BenchmarkRunner.RUNS` (`5`) |
| Warm-up iterations | Loop `i < 3` in `BenchmarkRunner`; single pass in `Main` |
| Fork/Join threshold | `new ForkJoinCounter(n)` or default `10` |
| Threshold list | `new int[]{1, 5, 10, 25, 50, 100}` in `BenchmarkRunner` |

## Correctness during benchmarks

`Main` asserts matching `totalWords` across strategies. `BenchmarkRunner` does **not** print the consistency check — if you change merge logic, run `Main` (or add an assert) before trusting speedups.