package com.yayuy.overgrownvegetation.spore;

/**
 * エンティティごとの「胞子への曝露」の一時カウンタ({@code ModAttachments.SPORE_EXPOSURE})。
 * {@link com.yayuy.overgrownvegetation.effect.SporeInfectionTracker} だけが読み書きする。
 * セーブしない(再ログイン後は 0 から数え直し)。
 */
public final class SporeExposure {

    /** 胞子濃度が感染帯にあるチャンクへの連続滞在 tick 数(プレイヤーの帯ロール / 高濃度帯の確定感染、Mob の滞在感染)。 */
    public int concentrationTicks;
    /** 過繫茂バイオームへの連続滞在 tick 数(感染状態を問わない。胞子感染2 への進行に使う)。 */
    public int overgrownBiomeTicks;
    /** 胞子感染2 を持った状態での過繫茂バイオームへの連続滞在 tick 数(胞子感染3 への進行に使う)。 */
    public int level2BiomeTicks;

    public void resetAll() {
        concentrationTicks = 0;
        overgrownBiomeTicks = 0;
        level2BiomeTicks = 0;
    }
}
