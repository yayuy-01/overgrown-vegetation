package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * クライアント → サーバー。パワードアーマー作成テーブルの「作成」ボタン(順 1 の着手順 5)。
 * レシピは番号ではなく id で送る(一覧の並びが両側でずれても取り違えない)。
 * サーバー側は {@link com.yayuy.overgrownvegetation.crafting.PoweredArmorTableCrafting#craft} が
 * テーブルを開いているか・研究の封印・素材を確かめる。
 */
public record PoweredArmorCraftPayload(ResourceLocation recipeId) implements CustomPacketPayload {

    public static final Type<PoweredArmorCraftPayload> TYPE =
            new Type<>(OvergrownVegetation.id("powered_armor_craft"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PoweredArmorCraftPayload> STREAM_CODEC =
            ResourceLocation.STREAM_CODEC.<RegistryFriendlyByteBuf>cast()
                    .map(PoweredArmorCraftPayload::new, PoweredArmorCraftPayload::recipeId);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
