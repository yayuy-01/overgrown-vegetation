package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * サーバー → クライアントへ「吐いた雲」1 つを伝える S2C ペイロード(2026-10-19。空の巨大生物が雲を吐いて身を隠す技、{@link com.yayuy.overgrownvegetation.sky.CloudPuffs})。
 *
 * <p>位置は雲の場の座標(fx, fz。風で流れる雲と同じ)と高さ y。start = 吐いたゲーム時刻、life = 長さ、grow = 膨らむ長さ、fade = 消える長さ(tick)、
 * seed = 形の乱数。形と膨らみ方・消え方はクライアントが同じ式で決める({@code client/CloudPuffs}、{@link com.yayuy.overgrownvegetation.sky.CloudPuffs#state})。
 * 10-19 に grow・fade を足した(雲の面の盛り上がり。ネットワークの版 12)。
 */
public record CloudPuffPayload(int id, double fx, double y, double fz, float radius, float height, long start, int life, int grow, int fade, long seed)
        implements CustomPacketPayload {

    public static final Type<CloudPuffPayload> TYPE = new Type<>(OvergrownVegetation.id("cloud_puff"));

    public static final StreamCodec<FriendlyByteBuf, CloudPuffPayload> STREAM_CODEC =
            StreamCodec.ofMember(CloudPuffPayload::write, CloudPuffPayload::read);

    private static CloudPuffPayload read(FriendlyByteBuf buf) {
        return new CloudPuffPayload(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat(),
                buf.readLong(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readLong());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(id);
        buf.writeDouble(fx);
        buf.writeDouble(y);
        buf.writeDouble(fz);
        buf.writeFloat(radius);
        buf.writeFloat(height);
        buf.writeLong(start);
        buf.writeVarInt(life);
        buf.writeVarInt(grow);
        buf.writeVarInt(fade);
        buf.writeLong(seed);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
