package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * サーバー → クライアントへ「このワールドでどの実験的要素がオンか」(2026-11-23 夜)。ログインしたときと値が変わったときに送る。
 * クライアントは研究の作り方の判定・クリエイティブのタブ・付け物の説明に使う({@code difficulty.ExperimentalFeatures})。
 *
 * @param mask オンの要素のビット({@code ExperimentalFeatures.Feature#ordinal()})
 */
public record ExperimentalPayload(int mask) implements CustomPacketPayload {

    public static final Type<ExperimentalPayload> TYPE = new Type<>(OvergrownVegetation.id("experimental"));

    public static final StreamCodec<FriendlyByteBuf, ExperimentalPayload> STREAM_CODEC =
            StreamCodec.ofMember((p, buf) -> buf.writeVarInt(p.mask), buf -> new ExperimentalPayload(buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
