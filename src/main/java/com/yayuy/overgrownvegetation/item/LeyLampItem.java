package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * 地脈灯の品(2026-10-22、地脈技術の 2 段目)。置けば地脈灯({@code block/LeyLampBlock})、腰の追加防具の枠に付ければ持ち歩く灯り。
 * <ul>
 *     <li>電池の残りは {@link ModDataComponents#BATTERY_CHARGE}(無い = 電池が入っていない)。置くとブロックへ移り、壊すと戻る。</li>
 *     <li>電池の入れ方: 持ち物の画面で地脈電池を持って地脈灯をクリック(陽溜まりのランタンの燃料と同じ入れ方。仮決め)。前の電池は手元(カーソル)に戻る。</li>
 *     <li>腰に付けている間は残りがあれば動く光(明るさ {@link #WORN_LIGHT}。琥珀の角灯と同じ口)。減り方は {@code defense/LeyLamps}。</li>
 * </ul>
 */
public class LeyLampItem extends BlockItem implements ExtraArmorItem {

    /** 腰に付けたときの動く光の明るさ(ユーザー指定)。 */
    public static final int WORN_LIGHT = 12;

    public LeyLampItem(Block block, Properties properties) {
        super(block, properties.stacksTo(1));
    }

    public static boolean hasBattery(ItemStack stack) {
        return stack.has(ModDataComponents.BATTERY_CHARGE.get());
    }

    public static int charge(ItemStack stack) {
        return Math.max(0, stack.getOrDefault(ModDataComponents.BATTERY_CHARGE.get(), 0));
    }

    public static void setCharge(ItemStack stack, int charge) {
        stack.set(ModDataComponents.BATTERY_CHARGE.get(), Math.max(0, Math.min(BatteryItem.CAPACITY, charge)));
    }

    /** 持ち物の画面で、地脈電池を持って地脈灯をクリックすると電池を入れ替える。 */
    @Override
    public boolean overrideOtherStackedOnMe(ItemStack stack, ItemStack other, Slot slot, ClickAction action, Player player, SlotAccess access) {
        if (action != ClickAction.PRIMARY || !other.is(ModItems.BATTERY.get()) || other.getCount() != 1 || !slot.allowModification(player)) {
            return false;
        }
        ItemStack back = hasBattery(stack) ? BatteryItem.stackFor(charge(stack)) : ItemStack.EMPTY;
        setCharge(stack, BatteryItem.charge(other));
        access.set(back);
        player.playSound(SoundEvents.COPPER_PLACE, 0.8F, 1.3F);
        ExtraArmor.broadcast(player); // 腰に付けたまま入れ替えたときに動く光を点け直す(サーバーだけで配る)
        return true;
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return hasBattery(stack);
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return Math.round(13.0F * charge(stack) / BatteryItem.CAPACITY);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return 0x4FA08C;
    }

    @Override
    public ExtraArmor.Part extraArmorPart() {
        return ExtraArmor.Part.WAIST;
    }

    @Override
    public ResourceLocation wornTexture() {
        return null;
    }

    @Override
    public int wornLightLevel() {
        return WORN_LIGHT;
    }

    @Override
    public int wornLightLevel(ItemStack stack) {
        return charge(stack) > 0 ? WORN_LIGHT : 0;
    }

    @Override
    public boolean renderWornItem() {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.overgrown_vegetation.extra_armor.waist").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.ley_lamp").withStyle(ChatFormatting.GRAY));
        if (hasBattery(stack)) {
            tooltip.add(Component.translatable("tooltip.overgrown_vegetation.ley_lamp.charge", charge(stack) / 1200, BatteryItem.CAPACITY / 1200)
                    .withStyle(ChatFormatting.DARK_AQUA));
        } else {
            tooltip.add(Component.translatable("tooltip.overgrown_vegetation.ley_lamp.no_battery").withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
