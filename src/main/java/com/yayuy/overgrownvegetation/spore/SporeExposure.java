package com.yayuy.overgrownvegetation.spore;

/**
 * エンティティごとの「胞子への曝露」の一時カウンタ({@code ModAttachments.SPORE_EXPOSURE})。
 * {@link com.yayuy.overgrownvegetation.effect.SporeInfectionTracker} だけが読み書きする。
 * セーブしない(再ログイン後は 0 から数え直し)。段階の進み(10 分)は保存する別の記録
 * ({@code ModAttachments.SPORE_STAGE_TICKS})。
 */
public final class SporeExposure {

    /** 胞子濃度が感染帯にあるチャンクへの連続滞在 tick 数(プレイヤーは 50% 以上に 30 秒、Mob の滞在感染)。 */
    public int concentrationTicks;
    /**
     * 暴露(濃度 % × 秒の積み、2026-10-23 胞子の場 第 2 段 D)。オーバーワールドで胞子の場を読むときに使う。
     * 濃い所を出ると半減期で減る。今の「50% に 30 秒」と同じ量(1500 %秒)で感染。
     */
    public double dose;
    /** 感染中のプレイヤーが経験値を吸われるまでの tick(10 秒ごと)。 */
    public int xpDrainTicks;
    /** 進行の加速(+5% / +10%)の端数の持ち越し(tick)。 */
    public double rateCarry;
    /** 濾し布の呼吸覆いの耐久を減らすまでの tick(濃い胞子の中だけ数える)。 */
    public int clothWearTicks;

    public void resetAll() {
        concentrationTicks = 0;
        dose = 0.0D;
        xpDrainTicks = 0;
        rateCarry = 0.0D;
        clothWearTicks = 0;
    }
}
