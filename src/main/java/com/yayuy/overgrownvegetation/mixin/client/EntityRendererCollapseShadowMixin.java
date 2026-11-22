package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.corpse.ClientCorpseCollapse;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 崩れ落ちから死体ブロックへ引き継いだ後の Mob は、体を描かないだけでなく影も消す(2026-10-09)。
 * 影は体の描画とは別に EntityRenderDispatcher が描くので、引き継ぎの間(約 1 秒)死体の下に Mob の丸い影が残っていた。
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererCollapseShadowMixin {

    @Inject(method = "getShadowRadius", at = @At("HEAD"), cancellable = true)
    private void overgrown_vegetation$noShadowAfterHandoff(Entity entity, CallbackInfoReturnable<Float> cir) {
        if (entity instanceof LivingEntity living && ClientCorpseCollapse.shouldHide(living)) {
            cir.setReturnValue(0.0F);
        }
    }
}
