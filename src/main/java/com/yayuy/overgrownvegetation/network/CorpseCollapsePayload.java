package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import com.yayuy.overgrownvegetation.port.network.StreamCodec;
import com.yayuy.overgrownvegetation.port.network.CustomPacketPayload;

/**
 * 胞子感染で死んだ Mob が死体の姿勢へ崩れ落ち始めた(S2C、2026-10-09)。{@code corpse.CorpseCollapse#begin} が
 * その Mob を見ているプレイヤーへ送る。クライアントはバニラの描画のまま死体の姿勢へ倒す。
 *
 * @param entityId 崩れ落ちる Mob
 * @param species  種の名前(崩れ落ちの姿勢 {@code corpse_poses/<名前>.json})
 * @param yaw      死んだときの体の向き(サーバーの yBodyRot。死体ブロックもこの向きで置く)
 */
public record CorpseCollapsePayload(int entityId, String species, float yaw) implements CustomPacketPayload {

    public static final Type<CorpseCollapsePayload> TYPE = new Type<>(OvergrownVegetation.id("corpse_collapse"));

    public static final StreamCodec<FriendlyByteBuf, CorpseCollapsePayload> STREAM_CODEC =
            StreamCodec.ofMember(CorpseCollapsePayload::write, CorpseCollapsePayload::new);

    private CorpseCollapsePayload(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readUtf(64), buf.readFloat());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeUtf(species, 64);
        buf.writeFloat(yaw);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
