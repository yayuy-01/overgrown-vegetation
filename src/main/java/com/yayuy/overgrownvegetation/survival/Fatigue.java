package com.yayuy.overgrownvegetation.survival;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * プレイヤー 1 人ぶんの疲労({@code ModAttachments.FATIGUE}、2026-11-13 サバイバルの背骨 段 1)。{@link FatigueTracker} だけが書く。
 * 保存するのは疲労の値だけ。死んだら引き継がない(添付の copyOnDeath を付けない = 復活は 0 から。ユーザー指定)。
 * エンドから戻るときの作り直し(死んでいない)では NeoForge が写す。
 */
public final class Fatigue {

    public static final Codec<Fatigue> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.DOUBLE.optionalFieldOf("value", 0.0D).forGetter(f -> f.value)
    ).apply(i, Fatigue::of));

    /** 疲労(0〜100 %)。 */
    public double value;

    /** 前の判定から走って(泳いで)いた tick(保存しない)。 */
    public int sprintTicks;
    /** 前の判定から殴った回数(溜めの割合で重み付け。連打は溜めが小さいので少ししか数えない。保存しない)。 */
    public double attacks;
    /** 前の判定から壊したブロックの数(保存しない)。 */
    public int breaks;
    /** 最後にクライアントへ送った HUD の値の鍵(保存しない。作り直された添付は -1 から = 復活・ログイン・次元の移動で送り直す)。 */
    public long sentKey = -1L;

    private static Fatigue of(double value) {
        Fatigue f = new Fatigue();
        f.value = value;
        return f;
    }

    /** 数えている途中の出来事を捨てる。 */
    public void clearPending() {
        sprintTicks = 0;
        attacks = 0;
        breaks = 0;
    }
}
