# secp256k1: JVM field-multiply experiments

Status: one change kept (`uLtInline`), two layouts tried and dropped. Measured with JMH
(kotlinx-benchmark 0.5.0, JDK 21, 3 forks x 5 iterations, latency chain `out = out * b`) on a
verbatim copy of `U256.mulWide` + `FieldP.reduceWide`. Each variant was checked against both
Quartz's code and `java.math.BigInteger` on 300,000 random and edge-case products (unreduced
inputs and `out` aliasing an input included) before timing.

| Variant | ns/op |
|---|---|
| Quartz today, before this work (xor-trick carries) | 38.1 |
| **Same code, carries via `Long.compareUnsigned`** | **27.7** |
| Fused single pass, column-wise accumulator in locals, branch-free fold, xor-trick carries | 42.3 |
| Fused single pass, `Long.compareUnsigned` carries | 28.1 |
| Fused single pass, Hacker's Delight carry-bit formula | 50.5 |
| 5x52 limbs, products split into 52-bit halves, no carry detection at all | 38.0 |
| Kotlin/Native, same Kotlin code with hardware multiply-high (for reference) | 13-15 |

## What it says

- **The carry idiom is what HotSpot cares about.** `(a xor MIN_VALUE) < (b xor MIN_VALUE)`
  compiles to noticeably worse code than `Long.compareUnsigned(a, b) < 0`. Kotlin's
  `a.toULong() < b.toULong()` compiles to the latter, so `uLtInline` now uses it (commit
  "compute carries with an unsigned compare HotSpot compiles well"): field multiply -27%,
  Schnorr verify 15.4k -> 20.7k ops/s in `Secp256k1Benchmark`.
- **Memory traffic is not the problem.** Keeping the whole 512-bit product in locals and fusing
  the reduction gains nothing over the two-function version: HotSpot already scalar-replaces
  the `Wide8` traffic.
- **52-bit limbs do not pay on the JVM.** Removing every compare costs 31 multiply-highs
  instead of 21, and comes out level with the original.
- **The remaining 2x to native is most likely the multiplies.** Kotlin on the JVM cannot ask
  for both halves of a 64x64 product, so each product is an `imul` for the low half plus a
  `mul` for the high half: 32 multiplies per field multiply where LLVM emits 16 `mul`s that
  yield both halves.

## JDK 27

Same JMH set, Temurin 27.0.1 against JDK 21, run back to back:

| Variant | JDK 21 | JDK 27 |
|---|---|---|
| Quartz, xor-trick carries | 37.6 ± 3.2 | 38.4 ± 2.6 |
| Quartz, `Long.compareUnsigned` carries | 28.1 ± 2.7 | 25.8 ± 2.5 |
| Fused, `Long.compareUnsigned` carries | 29.3 ± 2.1 | 28.1 ± 1.8 |
| 5x52 limbs | 39.1 ± 3.6 | 35.5 ± 3.5 |

At most ~8% better on the best variant, with overlapping error bars: JDK 27 does not close the
gap to native (13-15 ns). `Secp256k1Benchmark` (verify against ACINQ) on JDK 27 was too noisy
to read: ACINQ's own verify, identical C code, swung 21.7k-32.1k ops/s between runs.

## GraalVM and JDK 28

GraalVM CE 25.0.4.1 (Graal JIT on by default) on the same JMH set: the best variant (`Long.compareUnsigned`
carries) ran 27.7 ± 1.7 ns against 28.0 ± 1.3 on JDK 21 back to back. Graal compiles the xor-trick
and 5x52 variants better (30 vs 37-39 ns) but not the one Quartz uses. In `Secp256k1Benchmark`
Schnorr verify on Graal was 18.8k-20.6k ops/s, the same as JDK 21 (18.8k-19.2k).

OpenJDK mainline (JDK 28, built from source on 2026-11-07) has JDK-8379327: C2 fuses `a * b` with
`Math.multiplyHigh`/`Math.unsignedMultiplyHigh(a, b)` into one x86-64 `mul` (`MulHiLoL`/`UMulHiLoL`
nodes; x86-64 only). It does fire on `mulWide`, and it does not help:

| C2 code for `mulWide` | JDK 27 | JDK 28 |
|---|---|---|
| instructions | 321 | 316 |
| `imul` + `mul` | 16 + 16 | 0 + 16 |
| `mov` | 104 | 147 |
| `vmovq` (spills to xmm) | 32 | 0 |
| memory operands | 75 | 118 |

`mul` writes `rdx:rax`, so every product now passes through the same two registers, and C2 spills
around them to the stack. Timings, two rounds each, run back to back:

| Variant | JDK 21 | JDK 27 | JDK 28 |
|---|---|---|---|
| Quartz, `Long.compareUnsigned` carries | 26.5, 28.3 | 25.5, 25.7 | 26.0, 26.0 |
| Quartz, xor-trick carries | 40.0, 38.1 | 37.4, 38.5 | 29.2, 29.9 |
| 5x52 limbs | 39.4, 40.0 | 35.5, 35.1 | 25.8, 26.8 |

The fusion helps code that is limited by its multiplies (5x52 does 31 multiply-highs) and leaves the
fastest 4x64 code where it was: what limits that code is the compare-based carries and register
pressure, not the multiplier. C, for reference: the same algorithm under clang -O3 runs 14-18 ns,
the same as Kotlin/Native.

## Next candidates

1. Fewer multiplies: Karatsuba at the 2x2-limb level trades multiplies for additions; worth one
   measurement now that additions are cheaper.
2. Android/ART: re-measure all of the above on a device; ART's code generation differs from
   C2's, and `uLtInline` is common code.
