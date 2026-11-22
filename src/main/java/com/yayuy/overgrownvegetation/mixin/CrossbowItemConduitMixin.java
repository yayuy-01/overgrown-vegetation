package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.gear.ConduitWeapons;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 導晶の弩は、地脈の力がある間は装填が速い(2026-11-21 夜ユーザー決定「導晶機関で弦を巻く弩」)。
 * バニラの装填の時間 {@code CrossbowItem#getChargeDuration} は static で、装填の判定・音・手の構え・絵の引き具合がみなここを見るので、
 * 出口で導晶の弩のときだけ縮める({@link ConduitWeapons#chargeDuration})。ほかの弩(バニラ・ほかの MOD)は何もしない。
 */
@Mixin(CrossbowItem.class)
public abstract class CrossbowItemConduitMixin {

    @Inject(method = "getChargeDuration", at = @At("RETURN"), cancellable = true)
    private static void overgrown_vegetation$conduitFastLoad(ItemStack stack, LivingEntity shooter, CallbackInfoReturnable<Integer> cir) {
        int vanilla = cir.getReturnValueI();
        int ours = ConduitWeapons.chargeDuration(stack, vanilla);
        if (ours != vanilla) cir.setReturnValue(ours);
    }
}
