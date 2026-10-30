package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * クライアント → サーバー。背負った帆布の背嚢を開くキー(2026-10-16、㊱)。中身は無い。
 * サーバーは体の枠に本当に背嚢があるかを確かめてから開く。
 */
public record BackpackOpenPayload() implements CustomPacketPayload {

    public static final Type<BackpackOpenPayload> TYPE = new Type<>(OvergrownVegetation.id("backpack_open"));

    public static final StreamCodec<ByteBuf, BackpackOpenPayload> STREAM_CODEC = StreamCodec.unit(new BackpackOpenPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
