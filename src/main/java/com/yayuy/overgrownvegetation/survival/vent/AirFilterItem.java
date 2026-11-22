package com.yayuy.overgrownvegetation.survival.vent;

import org.jetbrains.annotations.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * <b>フィルター</b>(2026-11-14 段 2c)。防護マスクと地脈の換気機で同じ物を使う(決定 15)。材料はバニラだけ(決定 27: 紙・木炭・羊毛【仮】)。
 * 吸った胞子の分だけ詰まり、詰まり切ると使えない。洗って何度か使える(決定 18。洗い場は段 3。それまでは作り直す)。
 *
 * <p>使い方【仮】: 持ち物の画面でフィルターをつかみ、マスクの上で右クリック → 入れ替わる(古いフィルターが手に残る)。
 * 換気機へはフィルターを持って右クリック。式は {@link AirFilters}。
 */
public class AirFilterItem extends Item {

    public AirFilterItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return AirFilters.barVisible(stack);
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return AirFilters.barWidth(stack);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return AirFilters.barColor(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable net.minecraft.world.level.Level context, List<Component> tooltip, TooltipFlag flag) {
        if (AirFilters.clogged(stack)) {
            tooltip.add(Component.translatable("item.overgrown_vegetation.air_filter.clogged").withStyle(ChatFormatting.RED));
        } else {
            tooltip.add(Component.translatable("item.overgrown_vegetation.air_filter.left", AirFilters.percentLeft(stack))
                    .withStyle(ChatFormatting.GRAY));
        }
        if (AirFilters.washes(stack) > 0) {
            tooltip.add(Component.translatable("item.overgrown_vegetation.air_filter.washed", AirFilters.washes(stack))
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        tooltip.add(Component.translatable("item.overgrown_vegetation.air_filter.hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
