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
  compiles to noticeably worse code than `Long.compareUnsigned(a, b) < 0`, so `uLtInline` now
  uses `Long.compareUnsigned` on the JVM (field multiply -27%, Schnorr verify 15.4k -> 20.7k
  ops/s in `Secp256k1Benchmark`). It is expect/actual, like `uLt`: Android and native keep the
  xor trick, because `a.toULong() < b.toULong()` adds two `ULong.constructor-impl` calls per
  compare that ART does not remove.
- **But not for the square.** The same change made `U256.sqrWide` ~50% slower on HotSpot, which
  the multiply-only benchmark above did not show; batch verify (a square root per event, so
  mostly squarings) fell ~11% below the original. JMH, ns per square + reduction, two rounds:
  xor-trick square 30-34, `compareUnsigned` square 46-48; for the multiply the order is
  reversed (31-35 against 35-40), and the reduction is slightly faster with `compareUnsigned`.
  `sqrWide` therefore uses its own xor helper (`uLtXor`); after that, batch verify ran ~10% and
  single verify ~15% faster than the original code (alternating A/B, three rounds each).
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

## Experiment: teaching C2 to emit `adc`

To test whether the compare-based carries are what keeps the JVM behind C, JDK 28 was patched with
two x86-64 match rules (63 lines in `x86.ad`, the diff is in the appendix below):
`dst + (cond ? 1 : 0)` with `cond` an unsigned below / above-or-equal compare becomes `cmp` +
`adc dst, 0` (or `sbb dst, -1`), instead of today's `xor`, `cmp`, `mov $1`, `cmovb`, `add`. Kotlin's
`if (uLt(s, a)) 1L else 0L` produces exactly that shape, and no existing rule matched it.

The compiled code checked against BigInteger on 3.8 million products (mulWide's full 512-bit
product and reduceWide mod p) after C2 compilation.

| C2 code for `mulWide` | JDK 28 | JDK 28 + adc rules |
|---|---|---|
| instructions | 316 | 253 |
| `cmovb` / `adc` | 20 / 0 | 0 / 20 |
| memory operands | 118 | 113 |

Timings on a quieter machine than the tables above (two rounds each, back to back, ±0.3-1.2 ns):

| Variant | JDK 28 | JDK 28 + adc | JDK 28 + adc + `-XX:+OptoScheduling` |
|---|---|---|---|
| Quartz, `Long.compareUnsigned` carries | 21.5, 21.6 | 21.0, 21.4 | 19.4 |
| Fused, `Long.compareUnsigned` carries | 22.6, 23.2 | 21.4, 21.9 | 23.2 |
| C (clang -O3), same algorithm, same session | | | 14.8-15.4 |

Removing 20% of the instructions buys 1-5%. The hypothesis was wrong: in a dependent chain
(`out = out * b`, which is how verify and sign use the field) the carries are not on the critical
path in a way that costs much. What separates C2 from LLVM is the memory traffic: 113 memory
operands against LLVM's 29. C2 issues all 16 multiplies first and parks every result on the stack,
and every store-then-reload adds latency to the chain. Turning on C2's instruction scheduler
(`OptoScheduling`, off by default on x86) together with the adc rules gave the best result
(19.4 ns, -10%) but made the fused variant slower, so it is not a general fix either.

Closing the rest needs C2's register allocator and scheduler to keep the 8x4 multiply in
registers the way LLVM does, which is a much larger change than a match rule.

## Next candidates

1. Fewer multiplies: Karatsuba at the 2x2-limb level trades multiplies for additions; worth one
   measurement now that additions are cheaper.
2. Android/ART: re-measure all of the above on a device; ART's code generation differs from
   C2's, and `uLtInline` is common code.

## Appendix: the C2 adc patch

Against OpenJDK mainline `986c9a759` (2026-11-07).

```diff
diff --git a/src/hotspot/cpu/x86/x86.ad b/src/hotspot/cpu/x86/x86.ad
index 21d096eeb..8493e2602 100644
--- a/src/hotspot/cpu/x86/x86.ad
+++ b/src/hotspot/cpu/x86/x86.ad
@@ -6134,6 +6134,26 @@ operand cmpOpU()
 %}
 
 
+// An unsigned test that is exactly the carry flag (b) or its inverse (ae), for adc/sbb.
+operand cmpOpU_carry()
+%{
+  match(Bool);
+  predicate(n->as_Bool()->_test._test == BoolTest::lt ||
+            n->as_Bool()->_test._test == BoolTest::ge);
+
+  format %{ "" %}
+  interface(COND_INTER) %{
+    equal(0x4, "e");
+    not_equal(0x5, "ne");
+    less(0x2, "b");
+    greater_equal(0x3, "ae");
+    less_equal(0x6, "be");
+    greater(0x7, "a");
+    overflow(0x0, "o");
+    no_overflow(0x1, "no");
+  %}
+%}
+
 // Floating comparisons that don't require any fixup for the unordered case,
 // If both inputs of the comparison are the same, ZF is always set so we
 // don't need to use cmpOpUCF2 for eq/ne
@@ -9680,6 +9700,49 @@ instruct cmovL_imm_01U(rRegL dst, immL1 src, rFlagsRegU cr, cmpOpU cop)
   ins_pipe(ialu_reg);
 %}
 
+
+// dst + (cond ? 1 : 0) where cond is an unsigned below / above-or-equal test: the value
+// added is the carry flag, so add it with adc (below) or sbb -1 (above-or-equal, adds 1 - CF).
+// This is the carry idiom `s = a + b; carry = (s <u a) ? 1 : 0; hi += carry`.
+instruct addL_rReg_carryU(rRegL dst, cmpOpU_carry cop, rFlagsRegU cr, immL0 zero, immL1 one, rFlagsReg kill)
+%{
+  predicate(!UseAPX);
+  match(Set dst (AddL dst (CMoveL (Binary cop cr) (Binary zero one))));
+  match(Set dst (AddL (CMoveL (Binary cop cr) (Binary zero one)) dst));
+  effect(KILL kill);
+
+  ins_cost(100);
+  format %{ "adcq/sbbq $dst, carry($cop)\t# long += unsigned compare" %}
+  ins_encode %{
+    if ((Assembler::Condition)($cop$$cmpcode) == Assembler::below) {
+      __ adcq($dst$$Register, 0);
+    } else {
+      __ sbbq($dst$$Register, -1);
+    }
+  %}
+  ins_pipe(ialu_reg);
+%}
+
+// dst + (cond ? 0 : 1): the same with the constants swapped.
+instruct addL_rReg_carryU_inv(rRegL dst, cmpOpU_carry cop, rFlagsRegU cr, immL0 zero, immL1 one, rFlagsReg kill)
+%{
+  predicate(!UseAPX);
+  match(Set dst (AddL dst (CMoveL (Binary cop cr) (Binary one zero))));
+  match(Set dst (AddL (CMoveL (Binary cop cr) (Binary one zero)) dst));
+  effect(KILL kill);
+
+  ins_cost(100);
+  format %{ "adcq/sbbq $dst, !carry($cop)\t# long += unsigned compare" %}
+  ins_encode %{
+    if ((Assembler::Condition)($cop$$cmpcode) == Assembler::below) {
+      __ sbbq($dst$$Register, -1);
+    } else {
+      __ adcq($dst$$Register, 0);
+    }
+  %}
+  ins_pipe(ialu_reg);
+%}
+
 instruct cmovL_regU(cmpOpU cop, rFlagsRegU cr, rRegL dst, rRegL src)
 %{
   predicate(!UseAPX);
```
