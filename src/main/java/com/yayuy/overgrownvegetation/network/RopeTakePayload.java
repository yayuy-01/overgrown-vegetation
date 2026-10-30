package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * クライアント → サーバー。素手で何も無い所を右クリックして、紐に吊るした物を外す(2026-10-22、地脈技術の 2 段目)。中身は無い。
 * 何も無い所への素手の右クリックはサーバーに届かないので送る。サーバーはプレイヤーの視線で紐を選び直してから外す
 * ({@code defense/ClapperInteractions#takeLookedAt})。
 */
public record RopeTakePayload() implements CustomPacketPayload {

    public static final Type<RopeTakePayload> TYPE = new Type<>(OvergrownVegetation.id("rope_take"));

    public static final StreamCodec<ByteBuf, RopeTakePayload> STREAM_CODEC = StreamCodec.unit(new RopeTakePayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
