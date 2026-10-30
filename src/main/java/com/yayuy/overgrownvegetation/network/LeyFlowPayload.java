package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.ChunkPos;

/**
 * <b>地脈の通り道</b>(S2C、2026-10-16)。チャンクを見始めたプレイヤーへ、そのチャンクで地脈になる線(極相で地脈ブロックが敷かれる所)の
 * マスを送る。クライアントはこれに沿って、過繁茂の森で時々光の帯と粒を流す({@code client/LeyFlow})。
 * 線の場はワールドのシードで決まり、シードはクライアントへ送らないので、サーバーが計算して送る
 * ({@link com.yayuy.overgrownvegetation.corruption.LeyVeinField#flowAt})。線の通らないチャンクは送らない。
 *
 * <p>1 マス 1 つの long: 下位 8 ビット = チャンクの中の位置(z × 16 + x)、次の 8 ビット = 強さ(0〜255、線の中心で 255)、
 * 次の 16 ビット = 線の向きの角度(0〜65535 → 0〜2π)、上位 32 ビット = 線に沿った座標(float、ブロック)。
 *
 * @param chunk 対象のチャンク
 * @param cells 線の上のマス
 */
public record LeyFlowPayload(ChunkPos chunk, long[] cells) implements CustomPacketPayload {

    public static final Type<LeyFlowPayload> TYPE = new Type<>(OvergrownVegetation.id("ley_flow"));

    public static final StreamCodec<FriendlyByteBuf, LeyFlowPayload> STREAM_CODEC =
            StreamCodec.ofMember(LeyFlowPayload::write, LeyFlowPayload::new);

    private LeyFlowPayload(FriendlyByteBuf buf) {
        this(new ChunkPos(buf.readVarInt(), buf.readVarInt()), buf.readLongArray());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(chunk.x);
        buf.writeVarInt(chunk.z);
        buf.writeLongArray(cells);
    }

    /** 1 マスを詰める。 */
    public static long pack(int localX, int localZ, double strength, double dirX, double dirZ, double along) {
        long index = (localZ & 15) * 16L + (localX & 15);
        long s = Math.max(0, Math.min(255, Math.round(strength * 255.0)));
        double angle = Math.atan2(dirZ, dirX);
        if (angle < 0.0) {
            angle += Math.PI * 2.0;
        }
        long a = Math.max(0, Math.min(65535, Math.round(angle / (Math.PI * 2.0) * 65535.0)));
        long f = Float.floatToIntBits((float) along) & 0xFFFFFFFFL;
        return index | (s << 8) | (a << 16) | (f << 32);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
