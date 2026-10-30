package com.yayuy.overgrownvegetation.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 説明文を 1 行だけ持つ素材(2026-10-24、地脈技術の 3 段目の中間素材 = 忌避の精油・胞子の濾過膜)。
 * 説明文は言語ファイルの {@code tooltip.overgrown_vegetation.<id>}(灰色)。
 */
public class DescribedItem extends Item {

    public DescribedItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        String id = BuiltInRegistries.ITEM.getKey(this).getPath();
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation." + id).withStyle(ChatFormatting.GRAY));
    }
}
