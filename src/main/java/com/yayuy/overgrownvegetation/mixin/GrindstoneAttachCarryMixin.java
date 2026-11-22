package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.gear.attach.ArmorAttachments;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 防具の付け物: 砥石の結果に、上下の入力の付け物を引き継ぐ(2026-11-22 ユーザー「修理では消えない」)。
 * 結果に載せ切れない物は、受け取ったときに持ち主へ返す({@code AttachEffects#onGrindstoneTake})。
 */
@Mixin(GrindstoneMenu.class)
public abstract class GrindstoneAttachCarryMixin {

    @Inject(method = "computeResult", at = @At("RETURN"), cancellable = true)
    private void overgrown_vegetation$carryAttachments(ItemStack top, ItemStack bottom, CallbackInfoReturnable<ItemStack> cir) {
        ItemStack out = cir.getReturnValue();
        if (out.isEmpty() || (!ArmorAttachments.hasAny(top) && !ArmorAttachments.hasAny(bottom))) return;
        ItemStack copy = out.copy();
        ArmorAttachments.carryOver(copy, top, bottom);
        cir.setReturnValue(copy);
    }
}
