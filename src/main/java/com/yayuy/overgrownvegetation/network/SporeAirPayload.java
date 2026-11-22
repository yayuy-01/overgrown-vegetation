package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * サーバー → クライアントへ、プレイヤーの所の胞子濃度(%、{@code SporeConcentration#percentAt}。地点の補正込み)を伝える S2C ペイロード
 * (2026-10-30、密林の雰囲気の提案 2「光の筋は空気に胞子が濃いときにはっきり」)。{@code spore.field.SporeAirSync} が 5 秒ごとに送り、
 * クライアントは {@code client.atmosphere.AirHaze} が受ける。
 *
 * <p>2026-10-30 夜(第 2 段「動き」)から、今の卓越風({@code spore.field.PrevailingWind})の向き {@code windAngle}(ラジアン。風が吹いていく向き、
 * x = 東・z = 南)と強さ {@code windStrength}(夜の凪 0.15・午後 1・雨 0.6 以上・嵐 3)も相乗りする。場の無い次元では向きが NaN。
 * クライアントは {@code client.atmosphere.PrevailingWindClient} が受け、林の風の場の向きと嵐の強さの土台にする。
 */
public record SporeAirPayload(float percent, float windAngle, float windStrength) implements CustomPacketPayload {

    public static final Type<SporeAirPayload> TYPE = new Type<>(OvergrownVegetation.id("spore_air"));

    public static final StreamCodec<FriendlyByteBuf, SporeAirPayload> STREAM_CODEC =
            StreamCodec.ofMember(SporeAirPayload::write, SporeAirPayload::new);

    private SporeAirPayload(FriendlyByteBuf buf) {
        this(buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeFloat(percent);
        buf.writeFloat(windAngle);
        buf.writeFloat(windStrength);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
