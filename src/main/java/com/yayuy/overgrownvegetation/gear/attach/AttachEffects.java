package com.yayuy.overgrownvegetation.gear.attach;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.item.ProtectiveMaskItem;
import com.yayuy.overgrownvegetation.survival.vent.AirFilters;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.ItemAttributeModifierEvent;
import net.minecraftforge.event.ItemStackedOnOtherEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;

import java.util.List;
import com.yayuy.overgrownvegetation.port.entity.OvModifiers;

/**
 * 付け物の働きのうち、イベントで足すもの(2026-11-21 夜【仮】)。
 * <ul>
 *   <li>樹皮の当て板: 付けた部位の防御 +{@code armor}(1 枚ごと)。スタックの {@code ATTRIBUTE_MODIFIERS} には書かない(書くと防具自身の防御点を置き換える。10-03 の装飾品と同じ)。
 *   修飾は防具 1 つにつき 1 つに合算し、id に部位を入れる(同じ id が 2 つ付くと例外)。</li>
 *   <li>口覆いの布の付いた頭の防具: 持ち物の画面でフィルターを上で右クリックすると入れ替わる(防護マスクと同じ)。</li>
 *   <li>防具の説明に、付いている付け物とフィルターの残りを出す。</li>
 * </ul>
 * 導晶装飾の布(地脈の力の減り)は払う側から {@link ArmorAttachments#leyCostFactor} を読む。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class AttachEffects {

    private AttachEffects() {
    }

    @SubscribeEvent
    public static void onItemAttributes(ItemAttributeModifierEvent event) {
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof ArmorItem armor)) return;
        double bonus = ArmorAttachments.armorBonus(stack);
        String part = ArmorAttachments.partOf(stack);
        if (bonus == 0.0D || part == null) return;
        event.addModifier(Attributes.ARMOR,
                OvModifiers.create(OvergrownVegetation.id("armor_attachment." + part), bonus, AttributeModifier.Operation.ADDITION),
                EquipmentSlotGroup.bySlot(armor.getEquipmentSlot()));
    }

    @SubscribeEvent
    public static void onStackedOn(ItemStackedOnOtherEvent event) {
        ItemStack target = event.getStackedOnItem();
        ItemStack carried = event.getCarriedItem();
        if (event.getClickAction() != ClickAction.SECONDARY || target.getItem() instanceof com.yayuy.overgrownvegetation.gear.FilterMask) return;
        if (!ArmorAttachments.hasMouthCloth(target) || !AirFilters.isFilter(carried) || AirFilters.clogged(carried)) return;
        if (!event.getSlot().allowModification(event.getPlayer())) return;
        ProtectiveMaskItem.swapFilter(target, carried, event.getPlayer(), event.getCarriedSlotAccess());
        event.getPlayer().playSound(net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.8F, 1.2F);
        event.setCanceled(true);
    }

    /**
     * 設定の枠の数({@code slots})を減らしたとき、超えた所に残った付け物を持ち主へ返す(11-22 見直し 16)。2 秒に 1 回、持ち物と着ている物を見る。
     * 付け物の無い物はコンポーネントを見るだけで終わる。
     */
    @SubscribeEvent
    public static void onPlayerTick(com.yayuy.overgrownvegetation.port.event.tick.PlayerTickEvent.Post event) {
        var player = event.getEntity();
        if (player.level().isClientSide || player.tickCount % 40 != 0) return;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (ArmorAttachments.hasAny(s)) {
                java.util.List<ItemStack> over = ArmorAttachments.takeOverflow(s);
                if (!over.isEmpty()) ArmorAttachments.giveBack(player, over);
            }
        }
    }

    // ---- 修理で付け物を引き継ぐ(2026-11-22 ユーザー「修理では消えない」)。結果に載せ切れなかった物を持ち主へ ----

    /** 金床で受け取った(結果への引き継ぎは {@code AnvilAttachCarryMixin})。 */
    @SubscribeEvent
    public static void onAnvilTake(net.minecraftforge.event.entity.player.AnvilRepairEvent event) {
        ItemStack scratch = event.getOutput().copy();
        ArmorAttachments.giveBack(event.getEntity(), ArmorAttachments.carryOver(scratch, event.getLeft(), event.getRight()));
    }

    /** 砥石で受け取った(結果への引き継ぎは {@code GrindstoneAttachCarryMixin})。 */
    @SubscribeEvent
    public static void onGrindstoneTake(net.minecraftforge.event.GrindstoneEvent.OnTakeItem event) {
        ItemStack top = event.getTopItem(), bottom = event.getBottomItem();
        ItemStack scratch = new ItemStack(!top.isEmpty() ? top.getItem() : bottom.getItem());
        ArmorAttachments.giveBack(event.getPlayer(), ArmorAttachments.carryOver(scratch, top, bottom));
    }

    /**
     * 作業台で同じ防具 2 つを合わせて直した(バニラの修理のレシピは新しい物を作るので、付け物が消える)。結果へ付け直し、載らない物を返す。
     * 防具が 2 つ以上入っているときだけ(革の防具の染め直しなど、防具 1 つを写すレシピは結果がもう付け物を持っているので触らない)。
     * シフトで受け取ったときは結果がもう持ち物へ移っているので、付け物は全部持ち主へ返る(消えはしない)。
     */
    @SubscribeEvent
    public static void onCrafted(net.minecraftforge.event.entity.player.PlayerEvent.ItemCraftedEvent event) {
        var grid = event.getInventory();
        java.util.List<ItemStack> armors = new java.util.ArrayList<>();
        boolean any = false;
        for (int i = 0; i < grid.getContainerSize(); i++) {
            ItemStack s = grid.getItem(i);
            if (ArmorAttachments.canHold(s)) {
                armors.add(s);
                any |= ArmorAttachments.hasAny(s);
            }
        }
        if (armors.size() < 2 || !any) return;
        ArmorAttachments.giveBack(event.getEntity(), ArmorAttachments.carryOver(event.getCrafting(), armors.toArray(ItemStack[]::new)));
    }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!ArmorAttachments.canHold(stack)) return;
        List<Component> tip = event.getToolTip();
        List<ItemStack> list = ArmorAttachments.list(stack);
        int used = (int) list.stream().filter(s -> !s.isEmpty()).count();
        if (used == 0) return; // 何も付いていない防具には出さない
        int at = Math.min(tip.size(), 1);
        java.util.ArrayList<Component> lines = new java.util.ArrayList<>();
        lines.add(Component.translatable("armor_attachment.overgrown_vegetation.slots", used, ArmorAttachments.slotCount()).withStyle(ChatFormatting.DARK_GRAY));
        for (ItemStack s : list) {
            if (!s.isEmpty()) lines.add(Component.literal(" ・").append(s.getHoverName()).withStyle(ChatFormatting.GRAY));
        }
        if (ArmorAttachments.hasMouthCloth(stack)) {
            MouthClothItem.filterLine(stack, lines);
            lines.add(Component.translatable("item.overgrown_vegetation.protective_mask.swap_hint").withStyle(ChatFormatting.DARK_GRAY));
        }
        tip.addAll(at, lines);
    }
}
