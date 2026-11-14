package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.survival.decon.GearDirt;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 汚れた道具・防具は耐久が速く減る(2026-11-14 サバイバルの背骨 段 3b、決定 36)。{@code ItemStack#hurtAndBreak} の入口で減らす量を
 * 汚れの段ごとに増やす({@link GearDirt#wearAmount})。入口で量を変えるだけなので、この後のバニラの耐久エンチャント・NeoForge の
 * {@code Item#damageItem}・ほかの MOD の差し込みはそのまま効く(ぶつかりにくい形)。汚れていない物は何もしない。
 */
@Mixin(ItemStack.class)
public abstract class ItemStackDirtWearMixin {

    @ModifyVariable(method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Ljava/util/function/Consumer;)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int overgrown_vegetation$dirtyGearWearsFaster(int amount) {
        return GearDirt.wearAmount((ItemStack) (Object) this, amount);
    }
}
