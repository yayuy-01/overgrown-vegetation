package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * サーバー → クライアント「砲弾が消えた(着弾した)」。クライアントは該当する砲弾の描画をやめ、
 * 種類に応じた着弾の演出(閃光・音)を出す。
 *
 * @param id   砲弾の識別子({@link ShellFiredPayload#id()})
 * @param pos  着弾点(ワールド座標)
 * @param kind 種類({@link com.yayuy.overgrownvegetation.siege.ShellTracker#IMPACT_EXPIRE} など)
 * @param part ボスに命中したときの当たり判定パーツの添字(それ以外は -1)
 */
public record ShellImpactPayload(int id, Vec3 pos, int kind, int part) implements CustomPacketPayload {

    public static final Type<ShellImpactPayload> TYPE = new Type<>(OvergrownVegetation.id("shell_impact"));

    public static final StreamCodec<FriendlyByteBuf, ShellImpactPayload> STREAM_CODEC =
            StreamCodec.ofMember(ShellImpactPayload::write, ShellImpactPayload::new);

    private ShellImpactPayload(FriendlyByteBuf buf) {
        this(buf.readVarInt(), new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                buf.readVarInt(), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(id);
        buf.writeDouble(pos.x);
        buf.writeDouble(pos.y);
        buf.writeDouble(pos.z);
        buf.writeVarInt(kind);
        buf.writeVarInt(part);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
