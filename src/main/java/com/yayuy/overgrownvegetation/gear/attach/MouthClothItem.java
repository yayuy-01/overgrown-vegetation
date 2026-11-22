package com.yayuy.overgrownvegetation.gear.attach;

import org.jetbrains.annotations.Nullable;
import com.yayuy.overgrownvegetation.gear.FilterMask;
import com.yayuy.overgrownvegetation.item.ProtectiveMaskItem;
import com.yayuy.overgrownvegetation.survival.vent.AirFilters;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * <b>口覆いの布</b>(頭の付け物。2026-11-21 夜 ユーザー「防護マスクというか、口元を覆う布みたいなのにしたくて」)。
 * 防護マスクの働きを移した物: 中にフィルターを持ち、頭の防具に付けると胞子を防ぐ(兜と同時に使える)。
 *
 * <p>布そのものもフィルターを持つ入れ物({@link FilterMask})なので、持ち物の画面でフィルターを上で右クリックすると入れ替わり、洗い場で洗える。
 * 頭の防具に付いている間は、フィルターの状態は防具の側にある({@link ArmorAttachments#attach})。
 */
public class MouthClothItem extends ArmorAttachmentItem implements FilterMask {

    public MouthClothItem(Properties properties) {
        super(properties);
    }

    @Override
    public double filterWear() {
        return ArmorAttachments.mouthClothWear();
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
    public boolean overrideOtherStackedOnMe(ItemStack cloth, ItemStack other, Slot slot, ClickAction action, Player player, SlotAccess access) {
        // 付け物の台の枠の布は防具から作った写しなので、そこで入れ替えるとフィルターが増える(11-22 見直し・Fable)。入れ替えは外してから
        if (AttachBenchMenu.isAttachmentSlot(slot)) return false;
        if (action != ClickAction.SECONDARY || !AirFilters.isFilter(other) || AirFilters.clogged(other) || !slot.allowModification(player)) {
            return false;
        }
        ProtectiveMaskItem.swapFilter(cloth, other, player, access);
        player.playSound(net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_LEATHER, 0.8F, 1.2F);
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable net.minecraft.world.level.Level context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        filterLine(stack, tooltip);
    }

    /** フィルターの残りの行(布そのもの・布の付いた頭の防具で共通)。 */
    static void filterLine(ItemStack holder, List<Component> tooltip) {
        if (AirFilters.clogged(holder)) {
            tooltip.add(Component.translatable("item.overgrown_vegetation.protective_mask.spent").withStyle(ChatFormatting.RED));
        } else {
            tooltip.add(Component.translatable("item.overgrown_vegetation.protective_mask.filter", AirFilters.percentLeft(holder))
                    .withStyle(ChatFormatting.GRAY));
        }
    }
}
