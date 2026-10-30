package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * プレイヤーの追加防具の 4 枠の中身(S2C、2026-10-15、{@code item/ExtraArmor})。体に描く見た目を<b>そのプレイヤーを
 * 追跡している全員</b>に見せるため。枠の中身が変わったとき(サーバー側)と、誰かがそのプレイヤーを追跡し始めたときに送る。
 * 本人の分は持ち物の画面の枠の同期でも届く。
 *
 * @param entityId 対象のプレイヤーのエンティティ id
 * @param items    頭・体・腰・足の順の 4 つのスタック
 */
public record ExtraArmorPayload(int entityId, List<ItemStack> items) implements CustomPacketPayload {

    public static final Type<ExtraArmorPayload> TYPE = new Type<>(OvergrownVegetation.id("extra_armor"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ExtraArmorPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ExtraArmorPayload::entityId,
            ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()), ExtraArmorPayload::items,
            ExtraArmorPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
