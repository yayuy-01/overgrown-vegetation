package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * サーバー → クライアントへ「雲に開いた丸い穴」1 つを伝える S2C ペイロード(2026-10-19。空の巨大生物が雲海へ潜ったときの穴、{@link com.yayuy.overgrownvegetation.sky.CloudSurface})。
 *
 * <p>穴は縦の筒: 雲の場の座標 (fx, fz) で、高さ bottom〜top、半径 radius。start = 開いたゲーム時刻、open = 開ききるまで、fillFrom・fillTo = 埋まり始めと
 * 埋まりきり(開いてからの tick)。クライアントの {@code client/CloudHoles} が描く(雲のシェーダーが筒の中を捨てる)。
 */
public record CloudHolePayload(double fx, double fz, double bottom, double top, float radius, long start, int open, int fillFrom, int fillTo)
        implements CustomPacketPayload {

    public static final Type<CloudHolePayload> TYPE = new Type<>(OvergrownVegetation.id("cloud_hole"));

    public static final StreamCodec<FriendlyByteBuf, CloudHolePayload> STREAM_CODEC =
            StreamCodec.ofMember(CloudHolePayload::write, CloudHolePayload::read);

    private static CloudHolePayload read(FriendlyByteBuf buf) {
        return new CloudHolePayload(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readLong(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeDouble(fx);
        buf.writeDouble(fz);
        buf.writeDouble(bottom);
        buf.writeDouble(top);
        buf.writeFloat(radius);
        buf.writeLong(start);
        buf.writeVarInt(open);
        buf.writeVarInt(fillFrom);
        buf.writeVarInt(fillTo);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
