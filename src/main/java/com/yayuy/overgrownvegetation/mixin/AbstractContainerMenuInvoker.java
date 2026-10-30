package com.yayuy.overgrownvegetation.mixin;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@link AbstractContainerMenu} の protected なメソッドを呼ぶ口(追加防具の枠 {@code InventoryMenuExtraArmorMixin} から)。
 * {@code addSlot} などは親クラスにあるので、{@code InventoryMenu} の mixin で {@code @Shadow} すると起動で落ちる(メモリ参照)。
 */
@Mixin(AbstractContainerMenu.class)
public interface AbstractContainerMenuInvoker {

    @Invoker("addSlot")
    Slot overgrown_vegetation$addSlot(Slot slot);

    @Invoker("moveItemStackTo")
    boolean overgrown_vegetation$moveItemStackTo(ItemStack stack, int startIndex, int endIndex, boolean reverseDirection);
}
