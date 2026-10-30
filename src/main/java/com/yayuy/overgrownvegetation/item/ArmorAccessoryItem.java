package com.yayuy.overgrownvegetation.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 装飾品・拡張パーツのアイテム(順 1 の着手順 9、2026-10-03)。パワードアーマー作成テーブルの装着タブで防具に付ける。
 * 種類は {@link ArmorAccessory}。説明に付け先と効き目を出す(名前で付け先の種類が分かる —— ユーザー指定)。
 */
public class ArmorAccessoryItem extends Item {

    private final ArmorAccessory accessory;

    public ArmorAccessoryItem(ArmorAccessory accessory, Properties properties) {
        super(properties);
        this.accessory = accessory;
    }

    public ArmorAccessory accessory() {
        return accessory;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(accessory.placement().withStyle(ChatFormatting.GRAY));
        tooltip.add(accessory.effect().copy().withStyle(ChatFormatting.BLUE));
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.accessory.how").withStyle(ChatFormatting.DARK_GRAY));
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
