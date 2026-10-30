package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.menu.BackpackMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 帆布の背嚢(2026-10-16、㊱)。
 * <ul>
 *     <li>ユーザー指定: 体の追加防具の枠(布鎧と入れ替え)/ 18 枠 / 背負ったままキー(既定 B)で開く / 中身は背嚢に残る(外しても持ち運べる)。</li>
 *     <li>仮決め: 手に持って右クリックでも開く / 背嚢の中に背嚢は入れられない / 繊維質 7 + 革で作る。</li>
 * </ul>
 * 中身はバニラの {@code minecraft:container}(シュルカーボックスと同じ)に持つ。画面は {@link BackpackMenu}。
 */
public class CanvasBackpackItem extends Item implements ExtraArmorItem {

    public static final int SIZE = 18;

    public CanvasBackpackItem(Properties properties) {
        super(properties.stacksTo(1).component(DataComponents.CONTAINER, ItemContainerContents.EMPTY));
    }

    @Override
    public ExtraArmor.Part extraArmorPart() {
        return ExtraArmor.Part.BODY;
    }

    @Override
    public ResourceLocation wornTexture() {
        return null;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer sp) {
            int slot = hand == InteractionHand.MAIN_HAND ? sp.getInventory().selected : BackpackMenu.OFFHAND;
            open(sp, stack, slot);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    /**
     * 背嚢の画面を開く。{@code inventorySlot} は持ち物の中の背嚢の場所(持ち物にない = 背負っている、は {@link BackpackMenu#WORN})。
     * 開いている間はその場所の背嚢を動かせない。
     */
    public static void open(ServerPlayer player, ItemStack backpack, int inventorySlot) {
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.PLAYERS, 0.8F, 1.1F);
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new BackpackMenu(id, inv, backpack, inventorySlot),
                backpack.getHoverName()));
    }

    /** 背嚢の中身の写し(18 枠)。 */
    public static NonNullList<ItemStack> contents(ItemStack backpack) {
        NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        backpack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(items);
        return items;
    }

    public static void setContents(ItemStack backpack, List<ItemStack> items) {
        backpack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
    }

    @Override
    public boolean canFitInsideContainerItems() {
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.overgrown_vegetation.extra_armor.body").withStyle(ChatFormatting.GRAY));
        long used = contents(stack).stream().filter(s -> !s.isEmpty()).count();
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.canvas_backpack", used, SIZE,
                Component.keybind("key.overgrown_vegetation.backpack")).withStyle(ChatFormatting.DARK_GRAY));
    }
}
