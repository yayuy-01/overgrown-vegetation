package com.yayuy.overgrownvegetation.drift;

/** 漂流する擬態 Mob の数え(重さの確かめと {@code /ov drift stats})。 */
public final class DriftStats {

    private DriftStats() {
    }

    public static volatile long seeks, seekNanos, seekMaxNanos;
    public static volatile long swellSteps, swellNanos, swellMaxNanos, swellBlocks;
    public static volatile long ledgerSteps, ledgerNanos, ledgerMaxNanos;
    public static volatile int rooted, refused, launched, ferried, wentVirtual, cameBack, lost, bursts;

    static void seek(long nanos) {
        seeks++;
        seekNanos += nanos;
        if (nanos > seekMaxNanos) seekMaxNanos = nanos;
    }

    static void swell(long nanos, int blocks) {
        swellSteps++;
        swellNanos += nanos;
        swellBlocks += blocks;
        if (nanos > swellMaxNanos) swellMaxNanos = nanos;
    }

    static void ledger(long nanos) {
        ledgerSteps++;
        ledgerNanos += nanos;
        if (nanos > ledgerMaxNanos) ledgerMaxNanos = nanos;
    }

    public static String line() {
        return String.format(java.util.Locale.ROOT,
                "岸探し %d 回 平均 %.3f ms 最大 %.3f ms / 島の育ち %d 回 %d マス 平均 %.3f ms 最大 %.3f ms / 帳面 %d 回 平均 %.3f ms 最大 %.3f ms"
                        + " / 流した %d・運んだ %d・根付いた %d・断られた %d・外へ %d・戻った %d・沈んだ %d",
                seeks, seeks == 0 ? 0.0 : seekNanos / 1e6 / seeks, seekMaxNanos / 1e6,
                swellSteps, swellBlocks, swellSteps == 0 ? 0.0 : swellNanos / 1e6 / swellSteps, swellMaxNanos / 1e6,
                ledgerSteps, ledgerSteps == 0 ? 0.0 : ledgerNanos / 1e6 / ledgerSteps, ledgerMaxNanos / 1e6,
                launched, ferried, rooted, refused, wentVirtual, cameBack, lost);
    }

    public static void reset() {
        seeks = seekNanos = seekMaxNanos = 0;
        swellSteps = swellNanos = swellMaxNanos = swellBlocks = 0;
        ledgerSteps = ledgerNanos = ledgerMaxNanos = 0;
        rooted = refused = launched = ferried = wentVirtual = cameBack = lost = bursts = 0;
    }
}
