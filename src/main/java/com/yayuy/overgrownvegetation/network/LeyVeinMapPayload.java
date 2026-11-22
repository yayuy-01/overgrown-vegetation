package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 地脈の線までの距離の升(S2C、2026-11-14 シェーダーの仕上げ F「岩肌の地脈の筋」)。プレイヤーのまわり {@code size}×{@code size} 升
 * (1 升 {@link #CELL} ブロック、左上のブロック座標 {@code minX}・{@code minZ})。地脈の線の場({@code corruption/LeyLines})は
 * ワールドのシードから決まり、クライアントはシードを持たないので、線までの距離だけを送る(シードは送らない)。
 * <ul>
 *     <li>{@code distance}: 1 升 1 byte、{@code v = min(255, 距離 × 4)}(0〜63.75 ブロック)。</li>
 *     <li>{@code polluted}: 1 升 1 byte、その升のバイオームが汚染(過繁茂・繁茂)なら 255、違えば 0(筋の色: 汚染地域は地脈の根の色、通常世界は水色)。</li>
 * </ul>
 * {@code size} 0 = 地脈なし(オーバーワールドでない)。{@code ley/LeyVeinSync} が、プレイヤーが升の真ん中から離れたとき・バイオームが変わったときに送る。
 * クライアントは {@code client/atmosphere/LeyVeins} が受ける。
 */
public record LeyVeinMapPayload(int minX, int minZ, int size, byte[] distance, byte[] polluted) implements CustomPacketPayload {

    /** 1 升の大きさ(ブロック)。 */
    public static final int CELL = 4;

    public static final Type<LeyVeinMapPayload> TYPE = new Type<>(OvergrownVegetation.id("ley_vein_map"));

    public static final StreamCodec<FriendlyByteBuf, LeyVeinMapPayload> STREAM_CODEC =
            StreamCodec.ofMember(LeyVeinMapPayload::write, LeyVeinMapPayload::new);

    private LeyVeinMapPayload(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(), buf.readByteArray());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(minX);
        buf.writeVarInt(minZ);
        buf.writeVarInt(size);
        buf.writeByteArray(distance);
        buf.writeByteArray(polluted);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
