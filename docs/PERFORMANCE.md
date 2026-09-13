# Runtime and archive overhead comparison

Measured on 2026-09-13 with OpenJDK 21.0.12.1. The baseline is the working-tree build before these optimizations, including pre-existing local changes. Each value is a median of five runs with seed `20260910`; builds were measured sequentially without concurrent regression tests. Raw measurements and build hashes are in [performance-comparison.json](performance-comparison.json).

The published 0.2.2-beta benchmark is in [benchmark-0.2.2-beta.json](benchmark-0.2.2-beta.json). It verifies the same output checksum for every profile run.

| Profile | Metric | Before | After | Change |
| --- | --- | ---: | ---: | ---: |
| strong | JAR bytes | 82,889 | 69,908 | -15.7% |
| strong | Uncompressed class bytes | 141,617 | 118,802 | -16.1% |
| strong | Kernel time | 148.61 ms | 107.32 ms | -27.8% |
| strong | Whole process time | 473.73 ms | 399.82 ms | -15.6% |
| strong | Obfuscation time | 719.61 ms | 694.91 ms | -3.4% |
| full | JAR bytes | 302,849 | 289,749 | -4.3% |
| full | Uncompressed class bytes | 469,656 | 451,275 | -3.9% |
| full | Kernel time | 998.07 ms | 765.83 ms | -23.3% |
| full | Whole process time | 2,533.52 ms | 2,079.91 ms | -17.9% |
| full | Obfuscation time | 775.54 ms | 765.24 ms | -1.3% |

Input kernel time was 12.61 ms before and 12.85 ms after. Every input/output run returned checksum `79434320`; semanticCore protected three methods in both profiles and both builds. The fixture is small (7,146 bytes) and intentionally stresses arithmetic protection. It includes JVM warmup and compilation effects and is not a steady-state JMH benchmark or a prediction for a real application.

Changes:

- Input methods, including semantic bridges and input synthetic methods, remain eligible for anti-debug and exception-return protection. Generated arithmetic helpers no longer receive redundant guards and exception-return wrappers.
- Exception dispatch uses one stackless, suppression-disabled token. Results stay in invocation-local slots instead of mutable exception fields and ThreadLocal storage. Typed throw/catch routing remains in place.
- Unused field accessors and unused detector classes are removed before later passes expand them.
- Maximum DEFLATE compression reduces archive bytes without changing class contents.

No profile percentages or encryption settings were reduced. Helper layering is lower and return transport is simpler; equal reverse-engineering resistance has not been demonstrated. This comparison measures size, execution and semantics, not attack cost.

Validation: `python3 scripts/test-growth.py` (including RuntimeOverheadRegression, all shipped primary profiles, verifier, deterministic output, Java 17/21, module/Fabric/ServiceLoader fixtures) and `python3 scripts/test-cli.py`. Return tests cover primitives, raw NaN/signed-zero bits, object/array identity, void side effects, recursive and concurrent calls, and application exception propagation.

Reproduce after building:

```sh
python3 scripts/benchmark.py --jar /path/to/before.jar --runs 5 --profiles strong full
python3 scripts/benchmark.py --jar build/nullfuscator-obf.jar --runs 5 --profiles strong full
```
