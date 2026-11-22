package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import com.yayuy.overgrownvegetation.port.network.StreamCodec;
import com.yayuy.overgrownvegetation.port.network.CustomPacketPayload;

/**
 * 蠢く壁を殴った(C2S、2026-11-16)。塊はブロックでもエンティティでもないので、画面の側で視線が塊の升に当たったかを見て知らせる。
 * サーバーは升が本当に埋まっていて手の届く所かを確かめてから、壁をひるませる({@code wall/CreepingWalls#onHit})。
 *
 * @param cell 当たった升
 */
public record CreepingWallHitPayload(BlockPos cell) implements CustomPacketPayload {

    public static final Type<CreepingWallHitPayload> TYPE = new Type<>(OvergrownVegetation.id("creeping_wall_hit"));

    public static final StreamCodec<FriendlyByteBuf, CreepingWallHitPayload> STREAM_CODEC =
            StreamCodec.ofMember((p, buf) -> buf.writeBlockPos(p.cell()), buf -> new CreepingWallHitPayload(buf.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
