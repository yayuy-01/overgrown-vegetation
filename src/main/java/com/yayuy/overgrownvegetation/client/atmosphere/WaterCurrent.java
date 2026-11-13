package com.yayuy.overgrownvegetation.client.atmosphere;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;

/**
 * <b>水の中の共通の流れ</b>(2026-11-13、水まわり ⑥ の直し。ユーザー選択「共通の流れ: 漂う粒・沈む葉・泡・水草の揺れがみな同じ向きに流される」。
 * 理由: 粒・葉・泡・虫がそれぞれ勝手な向きに動いていて、同じ水の中にいるまとまりが無く「ドットが浮いているだけ」に見えた)。
 *
 * <p>世界に 1 つの卓越風({@link PrevailingWindClient}。雲や胞子を運ぶ風)の向きへ、水面の風が引く遅い流れ。向きは風の向きから
 * ゆっくり ±{@link #MEANDER} ラジアンさまよい、速さは {@link #BASE} + 風の強さ × {@link #PER_WIND}(ブロック/tick)。風が届く前は
 * 時刻だけでさまよう。値は急に跳ばないよう毎 tick 少しずつ寄せる。読み手: 水中の粒・沈む葉と綿・泡・泥煙・水草の傾き
 * ({@link LeafSway} が {@code OvCurrent} で渡す)。数値はすべて仮決め。
 */
public final class WaterCurrent {

    static final double BASE = 0.004;
    static final double PER_WIND = 0.003;
    static final double MAX_WIND = 3.0;
    static final double MEANDER = 0.5;

    private static float x;
    private static float z;

    static void tick(ClientLevel level) {
        double t = level.getGameTime() / 20.0;
        double angle = PrevailingWindClient.angle(level);
        double strength = PrevailingWindClient.strength(level);
        if (Double.isNaN(angle)) {
            angle = t / 900.0;
            strength = 0.5;
        }
        angle += Math.sin(t / 47.0) * MEANDER * 0.6 + Math.sin(t / 113.0 + 1.3) * MEANDER * 0.4;
        double speed = BASE + PER_WIND * Math.min(strength, MAX_WIND);
        float tx = (float) (Math.cos(angle) * speed);
        float tz = (float) (Math.sin(angle) * speed);
        x += (tx - x) * 0.02F;
        z += (tz - z) * 0.02F;
    }

    /** 流れの x・z(ブロック/tick)。 */
    public static float x() {
        return x;
    }

    public static float z() {
        return z;
    }

    /** 流れの速さを 0〜1 に(水草の傾きの大きさに)。 */
    static float strength01() {
        double max = BASE + PER_WIND * MAX_WIND;
        return (float) Mth.clamp(Math.sqrt(x * x + z * z) / max, 0.0, 1.0);
    }

    private WaterCurrent() {
    }
}
