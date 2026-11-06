package com.yayuy.overgrownvegetation.spore;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * エンティティ 1 体ぶんの胞子の暴露量({@code ModAttachments.SPORE_EXPOSURE}、2026-11-04 感染システムの第 1 段)。
 * {@link SporeExposureTracker} だけが書く。10-30 に消した前の版(感染の進みの数え)とは別物。
 *
 * <p>11-04 第 2 段から保存する({@link #CODEC}。暴露量と届いた記録だけ。端数の持ち越しは保存しない)。
 */
public final class SporeExposure {

    public static final Codec<SporeExposure> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.DOUBLE.optionalFieldOf("dose", 0.0D).forGetter(e -> e.dose),
            Codec.INT.optionalFieldOf("reached", 0).forGetter(e -> e.reachedCount),
            Codec.LONG.optionalFieldOf("last_reached", -1L).forGetter(e -> e.lastReachedGameTime),
            Codec.BOOL.optionalFieldOf("at_dose", false).forGetter(e -> e.atDose)
    ).apply(i, SporeExposure::of));

    /** 暴露量(濃度 % × 秒)。感染の量({@code spore_field.json} の exposure.infectDose)で頭打ち。 */
    public double dose;
    /**
     * 感染の量に届いた回数。プレイヤーは感染 1 にかかった回数、Mob は 20 ダメージを受けた回数(どちらも届くと暴露量を 0 に戻す、11-04 第 2 段)。
     */
    public int reachedCount;
    /** 最後に感染の量に届いたゲーム時刻(tick)。届いていなければ -1。 */
    public long lastReachedGameTime = -1L;
    /** 今、感染の量に届いたままか(感染中のプレイヤー・ダメージが通らなかった Mob。下がって半分を割ったら false)。 */
    public boolean atDose;

    /** 防護マスクの吸い取りの端数(%秒。1 未満の分を次へ持ち越す。保存しない)。 */
    public double maskCarry;
    /** 感染の進みの端数(tick。加速の小数を持ち越す。保存しない)。 */
    public double rateCarry;
    /** 経験値を吸うまでの tick(保存しない)。 */
    public int xpDrainTicks;
    /** 最後にクライアントへ送った暴露量の段(1/32 刻み。-1 = まだ送っていない。保存しない)。 */
    public int sentStep = -1;

    private static SporeExposure of(double dose, int reached, long last, boolean atDose) {
        SporeExposure e = new SporeExposure();
        e.dose = dose;
        e.reachedCount = reached;
        e.lastReachedGameTime = last;
        e.atDose = atDose;
        return e;
    }

    public void reset() {
        dose = 0.0D;
        reachedCount = 0;
        lastReachedGameTime = -1L;
        atDose = false;
        maskCarry = 0.0D;
        rateCarry = 0.0D;
        xpDrainTicks = 0;
    }
}
