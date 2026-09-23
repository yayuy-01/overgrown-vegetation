package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 亜種の種別(S2C、2026-09-19)。プレイヤーがその Mob を追跡し始めたときに 1 回送る
 * ({@code variant/MobVariants#onStartTracking})。クライアントはテクスチャの切り替えにだけ使う。
 *
 * @param entityId 対象のエンティティ id
 * @param variant  {@code MobVariant#code()}(1 = 通常 / 2 = 硬化 / 3 = 毒性 / 4 = 耐火)
 */
public record MobVariantPayload(int entityId, int variant) implements CustomPacketPayload {

    public static final Type<MobVariantPayload> TYPE = new Type<>(OvergrownVegetation.id("mob_variant"));

    public static final StreamCodec<FriendlyByteBuf, MobVariantPayload> STREAM_CODEC =
            StreamCodec.ofMember(MobVariantPayload::write, MobVariantPayload::new);

    private MobVariantPayload(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeVarInt(variant);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
