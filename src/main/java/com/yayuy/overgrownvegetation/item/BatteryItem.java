package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 地脈電池(2026-10-16 の蓄電の実の電池を 2026-10-22 に地脈電池へまとめた。ユーザー指定「電池は 1 種」「1 つで長時間動く」)。
 * 残りの電気は耐久で持つ(減った分 = 使った電気)。満タンは {@link #CAPACITY}(1 時間。仮決め。前は 10 分)。
 * 使い切ると空の地脈電池({@link EmptyBatteryItem})になる。地脈の汲み上げ器に入れると充電され、雷雨の中で外に置いても満たせる。
 * 作り方は真鍮インゴット 2 + 地脈の結晶の欠片 1 → 空の地脈電池(2026-10-22 ユーザー指定。前は蓄電の実で、蓄電の実ごと削除)。
 */
public class BatteryItem extends Item {

    public static final int CAPACITY = 72000;

    public BatteryItem(Properties properties) {
        super(properties.durability(CAPACITY));
    }

    public static int charge(ItemStack stack) {
        return stack.isEmpty() ? 0 : Math.max(0, stack.getMaxDamage() - stack.getDamageValue());
    }

    /** 残り {@code charge} の電池。0 なら空の地脈電池。 */
    public static ItemStack stackFor(int charge) {
        return charge > 0 ? withCharge(charge) : new ItemStack(ModItems.EMPTY_BATTERY.get());
    }

    public static ItemStack withCharge(int charge) {
        ItemStack s = new ItemStack(ModItems.BATTERY.get());
        s.setDamageValue(Math.max(0, CAPACITY - Math.min(CAPACITY, charge)));
        return s;
    }

    @Override
    public boolean isEnchantable(ItemStack stack) {
        return false;
    }

    @Override
    public boolean isValidRepairItem(ItemStack stack, ItemStack repair) {
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.battery", charge(stack) / 1200, CAPACITY / 1200)
                .withStyle(ChatFormatting.GRAY));
    }
}
