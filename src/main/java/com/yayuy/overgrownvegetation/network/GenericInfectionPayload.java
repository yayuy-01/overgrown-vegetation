package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 汎用の感染体の印(S2C、2026-11-15、{@code infect/GenericInfection})。変わった・変わり始めた・戻したときと、追跡を始めたときに送る。
 * クライアントは {@code client.infect.GenericInfectionClient#put} / {@code #remove} へ流し込む。
 *
 * @param entityId       その Mob
 * @param turned         false = 戻した(印を消す。ほかの値は使わない)
 * @param seed           個体ごとの乱数の種
 * @param startTick      変わり始めたゲーム時刻
 * @param transformTicks 変わる動きの長さ(tick)
 * @param boss           ボスとして変わったか
 */
public record GenericInfectionPayload(int entityId, boolean turned, long seed, long startTick, int transformTicks, boolean boss)
        implements CustomPacketPayload {

    public static final Type<GenericInfectionPayload> TYPE = new Type<>(OvergrownVegetation.id("generic_infection"));

    public static final StreamCodec<FriendlyByteBuf, GenericInfectionPayload> STREAM_CODEC =
            StreamCodec.ofMember(GenericInfectionPayload::write, GenericInfectionPayload::new);

    private GenericInfectionPayload(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readBoolean(), buf.readLong(), buf.readLong(), buf.readVarInt(), buf.readBoolean());
    }

    public static GenericInfectionPayload removed(int entityId) {
        return new GenericInfectionPayload(entityId, false, 0L, 0L, 0, false);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeBoolean(turned);
        buf.writeLong(seed);
        buf.writeLong(startTick);
        buf.writeVarInt(Math.max(0, transformTicks));
        buf.writeBoolean(boss);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
