package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 胞子の粉の見た目(S2C、2026-10-23 胞子の場と風 第 2 段 E の (1))。プレイヤーのまわり {@code size}×{@code size} チャンクの
 * 降り積もりの濃さ(0〜7。0 = 粉なし)と、シャベルで払った升の印(16 ビット、{@code masks})。
 * 2026-10-23 G から: 升ごとの浮遊の濃度 ÷ 2(%、0〜255。プルームの帯)、プレイヤーのいる所の濃度 × 10(緑の霧)、
 * 2026-10-24 から: 地割れの圧の前線 {中心 x, 中心 z, 今の半径, 速さ(ブロック/秒 × 100), 止まる半径, …}(ブロック。全方位へ広がる輪の帯)。
 * 近くの巨大樹の梢 {x, z, (梢の高さ << 8) | 育ち具合 0〜255, …}(葉から流れ出す胞子)。場の 1 回の更新ごとに、変わっていれば送る({@code spore/field/SporeDustSync})。
 */
public record SporeDustPayload(int minCx, int minCz, int size, byte[] levels, int[] masks, byte[] air, int hereTenths, int[] trees,
                               int[] fronts) implements CustomPacketPayload {

    public static final Type<SporeDustPayload> TYPE = new Type<>(OvergrownVegetation.id("spore_dust"));

    public static final StreamCodec<FriendlyByteBuf, SporeDustPayload> STREAM_CODEC =
            StreamCodec.ofMember(SporeDustPayload::write, SporeDustPayload::new);

    private SporeDustPayload(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(), buf.readVarIntArray(), buf.readByteArray(),
                buf.readVarInt(), buf.readVarIntArray(), buf.readVarIntArray());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(minCx);
        buf.writeVarInt(minCz);
        buf.writeVarInt(size);
        buf.writeByteArray(levels);
        buf.writeVarIntArray(masks);
        buf.writeByteArray(air);
        buf.writeVarInt(hereTenths);
        buf.writeVarIntArray(trees);
        buf.writeVarIntArray(fronts);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
