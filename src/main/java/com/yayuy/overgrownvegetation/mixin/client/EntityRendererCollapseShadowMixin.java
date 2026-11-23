package com.yayuy.overgrownvegetation.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.yayuy.overgrownvegetation.client.corpse.ClientCorpseCollapse;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 崩れ落ちから死体ブロックへ引き継いだ後の Mob は、体を描かないだけでなく影も消す(2026-10-09)。
 * 影は体の描画とは別に EntityRenderDispatcher が描くので、引き継ぎの間(約 1 秒)死体の下に Mob の丸い影が残っていた。
 *
 * <p>【1.20.1】本流は {@code EntityRenderer#getShadowRadius(Entity)} の頭で 0 を返す。1.20.1 にその関数は無く、
 * {@code EntityRenderDispatcher#render} が {@code EntityRenderer.shadowRadius} の値を直に読む(影を描くかの判定と大きさの 2 か所)。
 * そこで狙うクラスを {@code EntityRenderDispatcher} に変え(ファイル名は本流のまま)、その 2 回の読み出しを 0 にする。
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRendererCollapseShadowMixin {

    @ModifyExpressionValue(
            method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;shadowRadius:F", opcode = Opcodes.GETFIELD))
    private float overgrown_vegetation$noShadowAfterHandoff(float radius, @Local(argsOnly = true) Entity entity) {
        if (entity instanceof LivingEntity living && ClientCorpseCollapse.shouldHide(living)) {
            return 0.0F;
        }
        return radius;
    }
}
