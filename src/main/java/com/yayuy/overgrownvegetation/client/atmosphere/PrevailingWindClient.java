package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.network.SporeAirPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * サーバーから 5 秒おきに届く卓越風の向きと強さ({@link SporeAirPayload} に相乗り。2026-10-30 夜 第 2 段「動き」。元は parked の
 * {@code ClientPrevailingWind})。{@link GustField} の凪の向きと嵐の度合いの土台になる。届く前(サーバーにこの MOD が無い・まだ届いていない)は
 * 向きが NaN で、風の場は今までどおりさまよう。胞子濃度は {@link AirHaze} へそのまま渡す。
 *
 * <p>通信はワールドに入る途中(最初の tick より前)に届くことがあるので、受け取る所でワールドの替わりを見る
 * (memory client-payload-before-first-tick)。
 */
public final class PrevailingWindClient {

    private static double angle = Double.NaN;
    private static double strength;
    private static ClientLevel level;

    public static void accept(SporeAirPayload payload) {
        AirHaze.setSporePercent(payload.percent());
        level = Minecraft.getInstance().level;
        angle = payload.windAngle();
        strength = payload.windStrength();
    }

    /** 風が吹いていく向き(ラジアン)。届いていなければ NaN。 */
    static double angle(ClientLevel now) {
        return now != null && now == level ? angle : Double.NaN;
    }

    /** 卓越風の強さ(届いていなければ 0)。 */
    static double strength(ClientLevel now) {
        return now != null && now == level ? strength : 0.0;
    }

    private PrevailingWindClient() {
    }
}
