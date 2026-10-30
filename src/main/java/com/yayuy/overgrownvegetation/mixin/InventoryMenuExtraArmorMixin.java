package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.item.ExtraArmor;
import com.yayuy.overgrownvegetation.item.ExtraArmorContainer;
import com.yayuy.overgrownvegetation.item.ExtraArmorSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * プレイヤーの持ち物の画面({@link InventoryMenu})に<b>追加防具の 4 枠</b>を足す(2026-10-15 ユーザー指定、{@link ExtraArmor})。
 * <ul>
 *     <li>枠はプレイヤーの絵の右の列(x 77、y 8 / 26 / 44 / 62)。画面の枠の番号は 46〜49(サーバーとクライアントで同じ mixin が走るので揃う)。</li>
 *     <li>そこにあった左手の枠(番号 45)は、2×2 の作業枠の下(x 107、y 62)へ動かす(ユーザー指定)。</li>
 *     <li>シフトクリック: 持ち物から、追加防具のタグが付いた物をその枠へ入れる(枠から出すのはバニラの最後の分岐が持ち物へ戻す)。</li>
 * </ul>
 */
@Mixin(InventoryMenu.class)
public abstract class InventoryMenuExtraArmorMixin {

    private static final int OFFHAND_INDEX = 45;
    private static final int FIRST_EXTRA_INDEX = 46;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void overgrown_vegetation$addExtraArmorSlots(Inventory inventory, boolean active, Player owner, CallbackInfo ci) {
        InventoryMenu self = (InventoryMenu) (Object) this;
        Slot offhand = self.slots.get(OFFHAND_INDEX);
        ((SlotPositionAccessor) offhand).overgrown_vegetation$setX(107);
        ((SlotPositionAccessor) offhand).overgrown_vegetation$setY(62);
        ExtraArmorContainer container = new ExtraArmorContainer(owner);
        AbstractContainerMenuInvoker invoker = (AbstractContainerMenuInvoker) self;
        for (ExtraArmor.Part part : ExtraArmor.Part.values()) {
            invoker.overgrown_vegetation$addSlot(new ExtraArmorSlot(container, part, 77, 8 + part.ordinal() * 18));
        }
    }

    @Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
    private void overgrown_vegetation$quickMoveIntoExtraArmor(Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
        InventoryMenu self = (InventoryMenu) (Object) this;
        if (index < 9 || index >= FIRST_EXTRA_INDEX || self.slots.size() < FIRST_EXTRA_INDEX + ExtraArmor.SIZE) {
            return;
        }
        Slot slot = self.slots.get(index);
        if (!slot.hasItem()) {
            return;
        }
        ItemStack stack = slot.getItem();
        for (ExtraArmor.Part part : ExtraArmor.Part.values()) {
            int target = FIRST_EXTRA_INDEX + part.ordinal();
            if (!stack.is(part.tag()) || self.slots.get(target).hasItem()) {
                continue;
            }
            ItemStack copy = stack.copy();
            if (!((AbstractContainerMenuInvoker) self).overgrown_vegetation$moveItemStackTo(stack, target, target + 1, false)) {
                return;
            }
            if (stack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY, copy);
            } else {
                slot.setChanged();
            }
            slot.onTake(player, stack);
            cir.setReturnValue(copy);
            return;
        }
    }
}
