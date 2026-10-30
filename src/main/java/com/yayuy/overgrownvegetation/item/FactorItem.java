package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 「因子」(順 1 の着手順 8、2026-10-03)— 倒した生き物のドロップから抽出設備で取り出す小さなサンプル。
 * カートリッジに入れると、その種類の攻撃を 1 個につき {@code poweredArmor.factorPerItem}(2%)減らす。
 */
public class FactorItem extends Item {

    private final CartridgeFactor factor;

    public FactorItem(CartridgeFactor factor, Properties properties) {
        super(properties);
        this.factor = factor;
    }

    public CartridgeFactor factor() {
        return factor;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.factor",
                Component.translatable(factor.targetKey()),
                PoweredArmorItem.percent(DesignData.gear().poweredArmor().factorPerItem())).withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
