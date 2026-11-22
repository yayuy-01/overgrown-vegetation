package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import com.yayuy.overgrownvegetation.port.network.StreamCodec;
import com.yayuy.overgrownvegetation.port.network.CustomPacketPayload;

/**
 * 胞子の空気の升(S2C、2026-11-06 胞子の空気)。プレイヤーのまわり {@code size}×{@code size} チャンクの<b>浮遊</b>の濃度(林床は入れない。ユーザー決定)。
 * 1 升 1 byte: {@code v = round(255 × sqrt(min(%, 400) / 400))}(% = 浮遊 ÷ fullAt × 100。平方根で薄い 1〜5% も段にならずに残す)。
 * 全部 0 = 霞なし(場が止まっている・オーバーワールドでない)。{@code spore/field/SporeAirSync} が 5 秒ごとに、前と変わっていれば送る。
 * クライアントは {@code client/atmosphere/SporeAir} が受ける。
 */
public record SporeAirMapPayload(int minCx, int minCz, int size, byte[] levels) implements CustomPacketPayload {

    public static final Type<SporeAirMapPayload> TYPE = new Type<>(OvergrownVegetation.id("spore_air_map"));

    public static final StreamCodec<FriendlyByteBuf, SporeAirMapPayload> STREAM_CODEC =
            StreamCodec.ofMember(SporeAirMapPayload::write, SporeAirMapPayload::new);

    /** 1 byte の値 → 濃度 %。 */
    public static float decode(int v) {
        float s = (v & 0xFF) / 255.0F;
        return s * s * 400.0F;
    }

    /** 濃度 % → 1 byte の値。 */
    public static byte encode(double percent) {
        double p = Math.max(0.0, Math.min(400.0, percent));
        return (byte) Math.round(255.0 * Math.sqrt(p / 400.0));
    }

    private SporeAirMapPayload(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(minCx);
        buf.writeVarInt(minCz);
        buf.writeVarInt(size);
        buf.writeByteArray(levels);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
