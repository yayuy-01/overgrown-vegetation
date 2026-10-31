package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 胞子の粉の見た目(S2C、2026-10-31 合間の小物 3。元は 10-23 の同名の通信から粉の分だけを残した)。プレイヤーのまわり {@code size}×{@code size}
 * チャンクの降り積もり %(0〜100。0 = 粉なし。汚染済みのチャンクは 0)と、シャベルで払った升の印(チャンクごとの 16 ビット、{@code masks})。
 * 場の 1 回の更新ごとに、前と変わっていれば送る({@code spore/field/SporeDustSync})。クライアントは {@code client/ClientSporeDust} が受ける。
 */
public record SporeDustPayload(int minCx, int minCz, int size, byte[] levels, int[] masks) implements CustomPacketPayload {

    public static final Type<SporeDustPayload> TYPE = new Type<>(OvergrownVegetation.id("spore_dust"));

    public static final StreamCodec<FriendlyByteBuf, SporeDustPayload> STREAM_CODEC =
            StreamCodec.ofMember(SporeDustPayload::write, SporeDustPayload::new);

    private SporeDustPayload(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(), buf.readVarIntArray());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(minCx);
        buf.writeVarInt(minCz);
        buf.writeVarInt(size);
        buf.writeByteArray(levels);
        buf.writeVarIntArray(masks);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
