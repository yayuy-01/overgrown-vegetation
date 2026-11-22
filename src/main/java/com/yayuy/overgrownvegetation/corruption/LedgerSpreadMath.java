package com.yayuy.overgrownvegetation.corruption;


/**
 * 台帳まわりの<b>計算だけ</b>。世界にも台帳にも触らないので、GameTest から直接叩ける。
 * 2026-10-30: 止まっていた「5 分おきに縁を面の数 × 25%」の判定(回の数え方・確率・乱数の種)と極相の齢の判定を消した。
 * 2026-11-14: 地表の塗り残しの模様(四角い塗りが読んでいた patch・noiseThreshold)を消した。
 */
public final class LedgerSpreadMath {

    /** 1 日の長さ(tick)。 */
    public static final long TICKS_PER_DAY = 24000L;

    /** F1 開始から F5 までにかかった暦の長さ(tick)。逆転していたら 0。 */
    public static long phaseDelay(long phase1Clock, long phase5Clock) {
        return Math.max(0L, phase5Clock - phase1Clock);
    }


    private LedgerSpreadMath() {
    }
}
