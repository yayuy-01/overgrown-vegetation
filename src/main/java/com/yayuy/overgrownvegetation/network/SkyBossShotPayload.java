package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * サーバー → クライアントへ「空の巨大生物」の技の弾を伝える S2C ペイロード(2026-10-19。{@code skyboss/SkyBossShots})。
 * {@code op} = 0: 撃った(id・種類・撃った所・速さ・撃った tick・寿命。クライアントは同じ式で飛ばして描く)、1: 終わった(当たった・炸裂した所 x, y, z。
 * そこで消す。炸裂の粒はサーバーが別に送る)。
 */
public record SkyBossShotPayload(int op, int id, int kind, double x, double y, double z, double vx, double vy, double vz, long born, int life)
        implements CustomPacketPayload {

    public static final int SPAWN = 0;
    public static final int END = 1;

    public static final Type<SkyBossShotPayload> TYPE = new Type<>(OvergrownVegetation.id("sky_boss_shot"));
    public static final StreamCodec<FriendlyByteBuf, SkyBossShotPayload> STREAM_CODEC =
            StreamCodec.ofMember(SkyBossShotPayload::write, SkyBossShotPayload::read);

    public static SkyBossShotPayload end(int id, int kind, double x, double y, double z) {
        return new SkyBossShotPayload(END, id, kind, x, y, z, 0.0, 0.0, 0.0, 0L, 0);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeByte(op);
        buf.writeVarInt(id);
        buf.writeByte(kind);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        if (op == SPAWN) {
            buf.writeDouble(vx);
            buf.writeDouble(vy);
            buf.writeDouble(vz);
            buf.writeLong(born);
            buf.writeVarInt(life);
        }
    }

    private static SkyBossShotPayload read(FriendlyByteBuf buf) {
        int op = buf.readByte();
        int id = buf.readVarInt();
        int kind = buf.readByte();
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        if (op == SPAWN) {
            return new SkyBossShotPayload(op, id, kind, x, y, z, buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readLong(), buf.readVarInt());
        }
        return new SkyBossShotPayload(op, id, kind, x, y, z, 0.0, 0.0, 0.0, 0L, 0);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
