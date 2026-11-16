package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 蠢く壁の先頭の升(S2C、2026-11-16 試作。決定 その 7「先頭の升をサーバーから送る」)。
 *
 * @param wallId        壁の番号
 * @param kind          {@link #ADD} 新しく埋まった升 / {@link #SNAPSHOT} 今の先頭の送り直し / {@link #STOP} 止まった / {@link #CLEAR} 消す
 * @param base          升の座標の基準(受け取る人の近く)
 * @param cells         基準からのずれ((dx+128) << 16 | (dy+128) << 8 | (dz+128))。±127 まで
 * @param blocksPerTick 進む速さ(層の間の進み具合を補うため)
 */
public record CreepingWallPayload(int wallId, byte kind, BlockPos base, int[] cells, float blocksPerTick) implements CustomPacketPayload {

    public static final byte ADD = 0;
    public static final byte SNAPSHOT = 1;
    public static final byte STOP = 2;
    public static final byte CLEAR = 3;
    /** 前触れ: base = 始まりの升、cells[0] = 音を止める長さ(tick)。 */
    public static final byte OMEN = 4;

    public static final Type<CreepingWallPayload> TYPE = new Type<>(OvergrownVegetation.id("creeping_wall"));

    public static final StreamCodec<FriendlyByteBuf, CreepingWallPayload> STREAM_CODEC =
            StreamCodec.ofMember(CreepingWallPayload::write, CreepingWallPayload::read);

    private static CreepingWallPayload read(FriendlyByteBuf buf) {
        int id = buf.readVarInt();
        byte kind = buf.readByte();
        BlockPos base = buf.readBlockPos();
        int n = buf.readVarInt();
        int[] cells = new int[n];
        for (int i = 0; i < n; i++) {
            cells[i] = buf.readMedium();
        }
        return new CreepingWallPayload(id, kind, base, cells, buf.readFloat());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(wallId);
        buf.writeByte(kind);
        buf.writeBlockPos(base);
        buf.writeVarInt(cells.length);
        for (int c : cells) {
            buf.writeMedium(c);
        }
        buf.writeFloat(blocksPerTick);
    }

    /** 送る大きさ(バイト、見積もり)。 */
    public int approxBytes() {
        return 16 + 3 * cells.length;
    }

    public static int pack(int dx, int dy, int dz) {
        return (dx + 128) << 16 | (dy + 128) << 8 | (dz + 128);
    }

    public static int dx(int c) {
        return (c >> 16 & 0xFF) - 128;
    }

    public static int dy(int c) {
        return (c >> 8 & 0xFF) - 128;
    }

    public static int dz(int c) {
        return (c & 0xFF) - 128;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
