package com.yayuy.overgrownvegetation.client.atmosphere.heat;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.HeatFxTable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;

/**
 * <b>偽の空の昼夜</b>(2026-11-10 熱水の森のシェーダー段階 2。仕様の控え「偽物の空と昼夜」)。熱水の森は天井の植物が偽の空になり、
 * 昼は天井の植物が光を放ち、夜は光を溜めて周りが暗くなる(夜 = 最初の下絵の姿。決)。周期はオーバーワールドと同じ 20 分(決)、
 * 内訳は昼 10・夕 1.5・夜 7・朝 1.5 分(仮)。
 *
 * <p>時刻はクライアントのネザーの {@code dayTime}。ネザーのサーバーの世界はオーバーワールドの時刻をそのまま持ち({@code DerivedLevelData})、
 * プレイヤーには自分のいる世界の時刻が送られる({@code ClientboundSetTimePacket})ので、ネザーでも進む(11-10 にゲームで確かめた)。
 * ネザーの次元の {@code fixed_time} は空の角度(月や太陽)だけを止める。通信は足していない。
 */
public final class HeatFxSky {

    /** 確かめ用: 0 以上なら時刻をこの値(0〜23999)に固定する。 */
    static long devTime = -1L;

    private HeatFxSky() {
    }

    /** 今の時刻(0〜23999)。 */
    static float timeOfDay(ClientLevel level, float partialTick) {
        if (devTime >= 0L) {
            return devTime % 24000L;
        }
        return (float) ((level.getDayTime() % 24000L) + partialTick) % 24000.0F;
    }

    /** 昼の度合い(0 = 夜 〜 1 = 昼)。昼夜を切っていれば 0(いつも夜の姿)。 */
    public static float day() {
        float[] dp = dayAndDusk();
        return dp[0];
    }

    /** 夕と朝の紫の度合い(0〜1。夕と朝の真ん中で 1)。 */
    public static float dusk() {
        return dayAndDusk()[1];
    }

    private static float[] dayAndDusk() {
        HeatFxTable.Glow g = DesignData.heatFx().glow();
        ClientLevel level = Minecraft.getInstance().level;
        if (!g.dayNight || level == null) {
            return new float[]{0.0F, 0.0F};
        }
        float t = timeOfDay(level, Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false)) - g.dayStart;
        float cycle = g.dayTicks + g.duskTicks + g.nightTicks + g.dawnTicks;
        t = ((t % cycle) + cycle) % cycle;
        if (t < g.dayTicks) {
            return new float[]{1.0F, 0.0F};
        }
        t -= g.dayTicks;
        if (t < g.duskTicks) {
            float x = t / g.duskTicks;
            return new float[]{1.0F - HeatFxPresence.smooth(x), Mth.sin((float) Math.PI * x)};
        }
        t -= g.duskTicks;
        if (t < g.nightTicks) {
            return new float[]{0.0F, 0.0F};
        }
        t -= g.nightTicks;
        float x = Math.min(1.0F, t / g.dawnTicks);
        return new float[]{HeatFxPresence.smooth(x), Mth.sin((float) Math.PI * x)};
    }

    /** 名前(確かめ用)。 */
    static String describe() {
        ClientLevel level = Minecraft.getInstance().level;
        return "時刻 " + (level == null ? "-" : Math.round(timeOfDay(level, 0.0F))) + (devTime >= 0L ? "(固定)" : "") + " 昼 "
                + String.format("%.2f", day()) + " 紫 " + String.format("%.2f", dusk());
    }
}
