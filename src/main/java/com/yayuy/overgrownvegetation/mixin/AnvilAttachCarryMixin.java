package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.gear.attach.ArmorAttachments;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 防具の付け物: 金床の結果に、左右の入力の付け物を引き継ぐ(2026-11-22 ユーザー「修理では消えない」)。
 * 結果に載せ切れない物は、受け取ったときに持ち主へ返す({@code AttachEffects#onAnvilTake})。
 */
@Mixin(AnvilMenu.class)
public abstract class AnvilAttachCarryMixin {

    @Inject(method = "createResult", at = @At("TAIL"))
    private void overgrown_vegetation$carryAttachments(CallbackInfo ci) {
        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        ItemStack out = menu.getSlot(2).getItem();
        if (!out.isEmpty()) ArmorAttachments.carryOver(out, menu.getSlot(0).getItem(), menu.getSlot(1).getItem());
    }
}
