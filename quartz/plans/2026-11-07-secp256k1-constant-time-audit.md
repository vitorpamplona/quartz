# secp256k1: constant-time audit before replacing ACINQ

Status: findings, no fixes yet. Written while prototyping the hardware multiply-high on
Kotlin/Native (branch `claude/native-hw-mulhi`).

## Why this matters

ACINQ's secp256k1-kmp wraps libsecp256k1, whose secret-key operations run in constant time:
no branch and no memory address depends on a private key or a signing nonce. A pure-Kotlin
replacement must match that before it takes over **signing, key derivation and ECDH**.
**Verification** handles only public data (signature, message, public key), so variable-time
code is fine there; libsecp256k1's own verify is variable-time too.

Speed comparisons against ACINQ for signing and ECDH only count once the Kotlin side is
constant-time, and constant-time code is slower (see "Cost").

## Findings

Line numbers are at commit `0102e83` plus the prototype commits.

### High: secret-dependent table index and work in `ECPoint.mulG`

`mulG` computes `k·G` (the BIP-340 nonce point, `Secp256k1.kt:377`) and `d·G` (public keys).

- `ECPoint.kt:566` branches on each secret bit: `if (bitPos < 256 && U256.testBit(scalar, bitPos))`.
- `ECPoint.kt:570` skips the point addition when a comb window is zero: `if (mask != 0)`, so
  the number of additions depends on the scalar.
- `ECPoint.kt:571` indexes the precomputed table with secret bits:
  `table[block * COMB_POINTS + mask]`. Which cache lines are read reveals the window values.

libsecp256k1's `ecmult_gen` reads every entry of each window's table and keeps the wanted one
with a branch-free conditional move, and always performs the addition.

### High: variable-time wNAF in `ECPoint.mul` with a secret scalar

`ECPoint.mul` is used by `ecdhXOnly` (`Secp256k1.kt:622`, the NIP-04/NIP-44 shared secret with
the user's private key) and by `pubKeyTweakMul` (`Secp256k1.kt:580`).

- wNAF recoding (`ECPoint.kt:469`, `Glv.wnafInto`) is variable-time by design: the digit
  pattern, and so the sequence of additions, depends on the scalar.
- `ECPoint.kt:496` skips leading zero digits: the loop length depends on the scalar.
- `ECPoint.kt:513` adds only at nonzero digits and selects the table entry by the digit value.
- The GLV split and the negation flags (`split.negK1`) are secret-dependent too.

A constant-time variable-base multiply uses a fixed window (signed digits, every window does
an addition) with a full-table conditional-move lookup, like libsecp256k1's `ecmult_const`.

### Medium: secret-dependent negation in Schnorr signing

- `Secp256k1.kt:332`: `if (pubKeyHasEvenY)` decides whether to negate the private key.
- `Secp256k1.kt:383`: `if (KeyCodec.hasEvenY(ry))` decides whether to negate the nonce.

Each leaks about one bit per signature through a branch. Nonce leaks accumulate across
signatures, and lattice attacks need only a few bits per nonce. Use a branch-free conditional
negation (compute `n - x` and select with a mask).

### Medium: data-dependent branches in reductions

- `FieldP.reduceWide` (`FieldP.kt:459`, `467`, `471`, `475`, `483`): the second fold and its
  carry propagation run only when a carry is nonzero, with early exits.
- `FieldP.reduceSelf` (`FieldP.kt:388`): branches on whether the value is at least p.
- `ScalarN.reduceSelf` (`ScalarN.kt:153`) and the reductions at `ScalarN.kt:58` and `:71`:
  `if (cmp(a, N) >= 0) subtract`.

These run on values derived from secrets during signing and ECDH. The branches are rarely taken
for random-looking values, which limits the leak, but a constant-time implementation makes
every fold and the final subtraction unconditional (compute, then select with a mask). The 4x64
reduction can be made branch-free at little cost; one was written and checked against
BigInteger in the JMH experiments for the JVM field multiply.

### Low: `addMixed`, `addPoints`, `doublePoint` special cases

The point formulas branch on infinity and on doubling cases (`addMixed` has 3 top-level `if`s).
In a constant-time ladder those cases must be handled with complete formulas or masks.

### Fine as is

- `FieldP.inv` uses a fixed addition chain, so it is constant-time if multiply and square are.
- `ScalarN.inv` exponentiates by the public `N - 2`.
- Verification (`verifySchnorr`, batch verify): public inputs only.

## The JVM caveat

On the JVM and ART, `if (c) 1L else 0L` usually compiles to a conditional move, but nothing
guarantees it: the JIT may emit a branch based on profiling. Constant-time Kotlin on the JVM
should use arithmetic masks (`-(x ushr 63)`, `(a and m) or (b and m.inv())`) rather than
`if`. That is what BouncyCastle does in Java. It still relies on the JIT not reintroducing
branches, which needs checking in generated code (`-XX:+PrintAssembly` with hsdis) per JDK.

## Cost

Constant-time multiplication does more work: a full-table scan per window, an addition in
every window, no zero-skipping. Expect signing and ECDH to get slower than today's
variable-time Kotlin, plausibly by 1.3-2x, before tuning. libsecp256k1 pays the same price,
so the comparison with ACINQ becomes fair only then.

## Recommendation

1. Replace ACINQ for **verification** first (batch verify is already 5-10x faster than ACINQ).
2. Keep ACINQ for signing, key derivation and ECDH until constant-time `mulG`, constant-time
   variable-base `mul`, branch-free conditional negation and branch-free reductions exist, with
   tests that compare against ACINQ.
3. Add timing-variance tests (dudect-style: many runs with fixed vs random secret inputs,
   compare distributions) on JVM and native for each secret operation.
