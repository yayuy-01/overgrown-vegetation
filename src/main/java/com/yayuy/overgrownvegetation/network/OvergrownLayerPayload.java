package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.ChunkPos;

/**
 * <b>植物に覆われたマス</b>の記録(S2C、2026-09-22)。
 *
 * <p>覆われたマスは<b>置き換えられていない</b>(元のブロックのまま)ので、クライアントは
 * 「どこに苔を被せるか」をブロックからは知りようがない。この記録がその唯一の手がかり。
 *
 * <p>送るのは 2 つの場面だけ。
 * <ul>
 *     <li>チャンクを見始めたとき —— {@code replace = true} でまるごと</li>
 *     <li>新しく覆われたとき —— {@code replace = false} でそのぶんだけ(tick の終わりにまとめて)</li>
 * </ul>
 *
 * @param chunk     対象のチャンク
 * @param positions 覆われたマス({@link net.minecraft.core.BlockPos#asLong()})
 * @param replace   このチャンクの記録を入れ替えるか(false なら足す)
 */
public record OvergrownLayerPayload(ChunkPos chunk, long[] positions, boolean replace)
        implements CustomPacketPayload {

    public static final Type<OvergrownLayerPayload> TYPE = new Type<>(OvergrownVegetation.id("overgrown_layer"));

    public static final StreamCodec<FriendlyByteBuf, OvergrownLayerPayload> STREAM_CODEC =
            StreamCodec.ofMember(OvergrownLayerPayload::write, OvergrownLayerPayload::new);

    private OvergrownLayerPayload(FriendlyByteBuf buf) {
        this(new ChunkPos(buf.readVarInt(), buf.readVarInt()), buf.readLongArray(), buf.readBoolean());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(chunk.x);
        buf.writeVarInt(chunk.z);
        buf.writeLongArray(positions);
        buf.writeBoolean(replace);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
