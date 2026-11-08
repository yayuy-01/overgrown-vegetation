package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * サーバー → クライアントへ、雲を流した距離(2026-11-08 雲の段階 1「風を 1 つに」。{@code sky.CloudDrift})。
 * gameTime の時点で、場が風で運ばれた距離 (offsetX, offsetZ)(ブロック)と、今の雲の速さ (velX, velZ)(ブロック/tick)。
 * seed = 雲の模様の種({@code CloudField#setSeed}。ワールドのシードを混ぜた値。11-08 ユーザー決定 Q2)。style・coverage・floorThickness・floorFlatness = サーバーの雲の形の設計値(11-08 仕上げ 8。{@code sky.CloudShape})。5 秒ごとと、ワールドに入ったときに送る。クライアントは {@code client.sky.CloudDriftClient} が受け、次が届くまで速さで先読みする。
 */
public record CloudDriftPayload(long gameTime, double offsetX, double offsetZ, float velX, float velZ, long seed,
                                int style, float coverage, float floorThickness, float floorFlatness) implements CustomPacketPayload {

    public static final Type<CloudDriftPayload> TYPE = new Type<>(OvergrownVegetation.id("cloud_drift"));

    public static final StreamCodec<FriendlyByteBuf, CloudDriftPayload> STREAM_CODEC =
            StreamCodec.ofMember(CloudDriftPayload::write, CloudDriftPayload::new);

    private CloudDriftPayload(FriendlyByteBuf buf) {
        this(buf.readLong(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat(), buf.readLong(), buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeLong(gameTime);
        buf.writeDouble(offsetX);
        buf.writeDouble(offsetZ);
        buf.writeFloat(velX);
        buf.writeFloat(velZ);
        buf.writeLong(seed);
        buf.writeVarInt(style);
        buf.writeFloat(coverage);
        buf.writeFloat(floorThickness);
        buf.writeFloat(floorFlatness);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
