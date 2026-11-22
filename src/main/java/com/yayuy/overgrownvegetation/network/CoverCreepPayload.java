package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import com.yayuy.overgrownvegetation.port.network.StreamCodec;
import com.yayuy.overgrownvegetation.port.network.CustomPacketPayload;
import net.minecraft.world.level.ChunkPos;

/**
 * <b>這い上がる苔の記録</b>(S2C、2026-11-01。{@code corruption/CoverCreepData})。送り方は覆いの記録({@link OvergrownLayerPayload})と同じ:
 * チャンクを見始めたとき {@code replace} でまるごと、置いた・消したときはそのぶんだけ(tick の終わりにまとめて)。
 *
 * @param chunk     対象のチャンク
 * @param positions マス({@link net.minecraft.core.BlockPos#asLong()})
 * @param values    マスごとの {@code (置いたときのゲーム時刻 << 6) | 向きの印}(remove のときは空)
 * @param replace   このチャンクの記録を入れ替えるか
 * @param remove    このマスを記録から消すか
 */
public record CoverCreepPayload(ChunkPos chunk, long[] positions, long[] values, boolean replace, boolean remove)
        implements CustomPacketPayload {

    public static final Type<CoverCreepPayload> TYPE = new Type<>(OvergrownVegetation.id("cover_creep"));

    public static final StreamCodec<FriendlyByteBuf, CoverCreepPayload> STREAM_CODEC =
            StreamCodec.ofMember(CoverCreepPayload::write, CoverCreepPayload::new);

    private CoverCreepPayload(FriendlyByteBuf buf) {
        this(new ChunkPos(buf.readVarInt(), buf.readVarInt()), buf.readLongArray(), buf.readLongArray(), buf.readBoolean(), buf.readBoolean());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(chunk.x);
        buf.writeVarInt(chunk.z);
        buf.writeLongArray(positions);
        buf.writeLongArray(values);
        buf.writeBoolean(replace);
        buf.writeBoolean(remove);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
