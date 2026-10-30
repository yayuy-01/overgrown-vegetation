package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.spore.GearContamination;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 胞子汚染({@link GearContamination})した武器・道具・盾は、<b>1 回の使用で減る耐久値が増える</b>
 * (2026-10-14 ユーザー指定: 武器・道具は最大 3 倍、盾は最大 2 倍)。
 *
 * <p>耐久値を減らす入口はすべて {@code hurtAndBreak(int, ServerLevel, LivingEntity, Consumer)} を通る
 * (攻撃・採掘・盾で防ぐ・トライデントを投げる)。先頭で減る量を増やすので、耐久力のエンチャントは
 * 増えた後の量に効く(1 ずつ確率で打ち消す)。
 */
@Mixin(ItemStack.class)
public abstract class ItemStackDurabilityMixin {

    @Unique
    private static final RandomSource OVERGROWN_VEGETATION$RANDOM = RandomSource.createThreadSafe();

    @ModifyVariable(
            method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Ljava/util/function/Consumer;)V",
            at = @At("HEAD"), argsOnly = true)
    private int overgrown_vegetation$contaminatedWear(int damage) {
        ItemStack self = (ItemStack) (Object) this;
        if (GearContamination.percent(self) <= 0.0F) {
            return damage;
        }
        return GearContamination.scaleDurabilityDamage(self, damage, OVERGROWN_VEGETATION$RANDOM);
    }
}
