package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * サーバー → クライアントへ「積乱雲の中を走る雷」1 本を伝える S2C ペイロード(2026-10-17。ユーザー指定「雷雨の際の積乱雲のみで、雲の中に雷が走る。触れたら即死」)。
 *
 * <p>雷はサーバーが決め({@link com.yayuy.overgrownvegetation.sky.CloudLightning})、当たったかもサーバーが調べる。クライアントは受け取った線をそのまま描く
 * ({@code client/Thunderstorms})。線の点は origin からのずれ(float)。branch = 枝の始まりの点の番号(そこから後ろが枝。無ければ点の数)。charge = 前触れ(当たらない)の tick、strike = 走っている(触れると死ぬ)tick。
 */
public record CloudBoltPayload(double x, double y, double z, float[] points, int branch, int charge, int strike) implements CustomPacketPayload {

    public static final Type<CloudBoltPayload> TYPE = new Type<>(OvergrownVegetation.id("cloud_bolt"));

    public static final StreamCodec<FriendlyByteBuf, CloudBoltPayload> STREAM_CODEC =
            StreamCodec.ofMember(CloudBoltPayload::write, CloudBoltPayload::read);

    private static CloudBoltPayload read(FriendlyByteBuf buf) {
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        int n = Math.min(buf.readVarInt(), 64);
        float[] points = new float[n * 3];
        for (int i = 0; i < points.length; i++) {
            points[i] = buf.readFloat();
        }
        return new CloudBoltPayload(x, y, z, points, buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeVarInt(points.length / 3);
        for (float p : points) {
            buf.writeFloat(p);
        }
        buf.writeVarInt(branch);
        buf.writeVarInt(charge);
        buf.writeVarInt(strike);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
