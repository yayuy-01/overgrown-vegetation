package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * クライアント → サーバー。いまの<b>見える距離</b>(描画距離と地形の霧の終わりの近い方、ブロック。2026-09-26)。
 * サーバーは TD の仮想の群れをその外で生ませる({@code td/TdSight}。ユーザー指定「外周の霧の中から出てくる感じに」)。
 * 変わったときだけ送る({@code client/horde/HordeSight})。
 */
public record HordeSightPayload(int blocks) implements CustomPacketPayload {

    public static final Type<HordeSightPayload> TYPE = new Type<>(OvergrownVegetation.id("horde_sight"));

    public static final StreamCodec<FriendlyByteBuf, HordeSightPayload> STREAM_CODEC =
            StreamCodec.ofMember(HordeSightPayload::write, HordeSightPayload::new);

    private HordeSightPayload(FriendlyByteBuf buf) {
        this(buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(blocks);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
