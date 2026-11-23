package com.yayuy.overgrownvegetation.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.yayuy.overgrownvegetation.gear.attach.ArmorAttachments;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 防具の付け物: 砥石の結果に、上下の入力の付け物を引き継ぐ(2026-11-22 ユーザー「修理では消えない」)。
 * 結果に載せ切れない物は、受け取ったときに持ち主へ返す({@code AttachEffects#onGrindstoneTake})。
 *
 * <p>1.20.1: 本流の {@code computeResult(top, bottom)}(結果を返す)は無く、{@code createResult()} が結果の枠へ直に入れる。
 * 結果が出る道は 1 つだけ({@code removeNonCurses} の戻り値を枠へ入れる所)なので、その戻り値を差し替える。上下の入力は {@code repairSlots} の 0・1。
 * Forge の {@code onGrindstoneChange} が結果を決めた時は、本流と同じくこの道を通らない。
 */
@Mixin(GrindstoneMenu.class)
public abstract class GrindstoneAttachCarryMixin {

    @Shadow
    @Final
    Container repairSlots;

    @ModifyExpressionValue(method = "createResult",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/inventory/GrindstoneMenu;removeNonCurses(Lnet/minecraft/world/item/ItemStack;II)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack overgrown_vegetation$carryAttachments(ItemStack out) {
        ItemStack top = this.repairSlots.getItem(0);
        ItemStack bottom = this.repairSlots.getItem(1);
        if (out.isEmpty() || (!ArmorAttachments.hasAny(top) && !ArmorAttachments.hasAny(bottom))) return out;
        ItemStack copy = out.copy();
        ArmorAttachments.carryOver(copy, top, bottom);
        return copy;
    }
}
