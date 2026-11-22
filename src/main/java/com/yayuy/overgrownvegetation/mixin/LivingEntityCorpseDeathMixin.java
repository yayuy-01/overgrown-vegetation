package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.corpse.CorpseCollapse;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 胞子感染で死体へ崩れ落ちる Mob の死の時計(2026-10-09)。サーバー側だけ、バニラの {@code tickDeath}
 * (20 tick で煙を出して消す)の代わりに数え、{@link CorpseCollapse#COLLAPSE_TICKS} で死体ブロックに入れ替える。
 * クライアントはバニラのまま(deathTime が増え、それを崩れ落ちの進みに使う)。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityCorpseDeathMixin {

    @Inject(method = "tickDeath", at = @At("HEAD"), cancellable = true)
    private void overgrown_vegetation$collapseIntoCorpse(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide() || !CorpseCollapse.isCollapsing(self)) {
            return;
        }
        CorpseCollapse.tickDeath(self);
        ci.cancel();
    }
}
