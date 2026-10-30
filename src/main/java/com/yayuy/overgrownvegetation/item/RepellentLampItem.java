package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * 忌避灯の品(2026-10-24、地脈技術の 3 段目で電池式に)。電池の残りは地脈灯と同じく {@link ModDataComponents#BATTERY_CHARGE}
 * (無い = 電池が入っていない)。置くとブロックへ移り、壊すと戻る。電池の入った地脈灯から作ると、その電池を引き継ぐ({@code defense/MachineBattery})。
 */
public class RepellentLampItem extends BlockItem {

    public RepellentLampItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.repellent_lamp").withStyle(ChatFormatting.GRAY));
        Integer charge = stack.get(ModDataComponents.BATTERY_CHARGE.get());
        if (charge != null) {
            tooltip.add(Component.translatable("tooltip.overgrown_vegetation.ley_lamp.charge", charge / 1200, BatteryItem.CAPACITY / 1200)
                    .withStyle(ChatFormatting.DARK_AQUA));
        } else {
            tooltip.add(Component.translatable("tooltip.overgrown_vegetation.ley_lamp.no_battery").withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
