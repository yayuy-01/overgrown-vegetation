package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * サーバー → クライアントへ、プレイヤーの所の胞子濃度(%、{@code SporeConcentration#percentAt}。地点の補正込み)を伝える S2C ペイロード
 * (2026-10-30、密林の雰囲気の提案 2「光の筋は空気に胞子が濃いときにはっきり」)。{@code spore.field.SporeAirSync} が 5 秒ごとに送り、
 * クライアントは {@code client.atmosphere.AirHaze} が受ける。
 */
public record SporeAirPayload(float percent) implements CustomPacketPayload {

    public static final Type<SporeAirPayload> TYPE = new Type<>(OvergrownVegetation.id("spore_air"));

    public static final StreamCodec<FriendlyByteBuf, SporeAirPayload> STREAM_CODEC =
            StreamCodec.ofMember(SporeAirPayload::write, SporeAirPayload::new);

    private SporeAirPayload(FriendlyByteBuf buf) {
        this(buf.readFloat());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeFloat(percent);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
