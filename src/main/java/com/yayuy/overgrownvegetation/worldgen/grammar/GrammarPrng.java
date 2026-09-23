package com.yayuy.overgrownvegetation.worldgen.grammar;

/**
 * xoroshiro128++(Minecraft 1.21 の {@code Xoroshiro128PlusPlus} / {@code RandomSupport} と同一)。
 * ブラウザ側 {@code tools/structure-grammar/src/prng.js} と完全に一致させる。仕様は
 * {@code tools/structure-grammar/SPEC.md} の §4。
 *
 * <p>Minecraft に依存しない(意図的)。ゴールデンテストが素の JUnit で回せるようにするため。
 */
public final class GrammarPrng {

    private static final long SILVER = 0x6a09e667f3bcc909L;
    private static final long GOLDEN = 0x9e3779b97f4a7c15L;

    private long lo;
    private long hi;
    /** 消費した {@link #nextLong()} の回数(fixtures の {@code draws} と照合)。 */
    public long draws;

    public GrammarPrng(long seed) {
        long i = seed ^ SILVER;
        long j = i + GOLDEN;
        this.lo = mixStafford13(i);
        this.hi = mixStafford13(j);
        if ((this.lo | this.hi) == 0L) {
            this.lo = GOLDEN;
            this.hi = SILVER;
        }
    }

    private static long mixStafford13(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    public long nextLong() {
        draws++;
        long l = this.lo;
        long m = this.hi;
        long n = Long.rotateLeft(l + m, 17) + l;
        m ^= l;
        this.lo = Long.rotateLeft(l, 49) ^ m ^ (m << 21);
        this.hi = Long.rotateLeft(m, 28);
        return n;
    }

    /** {@code [0, 1)} の double。SPEC §4.2 nextUnit()。 */
    public double nextUnit() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /** SPEC §4.2 nextIntBounded(n) — Lemire。MC {@code Xoroshiro…nextInt(int)} と同一。 */
    public int nextIntBounded(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("nextIntBounded: bound must be > 0, got " + n);
        }
        long bound = n;
        long lo32 = nextLong() & 0xffffffffL;
        long m = lo32 * bound;
        long low = m & 0xffffffffL;
        if (low < bound) {
            long t = ((1L << 32) - bound) % bound; // 2^32 % bound
            while (low < t) {
                lo32 = nextLong() & 0xffffffffL;
                m = lo32 * bound;
                low = m & 0xffffffffL;
            }
        }
        return (int) (m >>> 32);
    }
}
