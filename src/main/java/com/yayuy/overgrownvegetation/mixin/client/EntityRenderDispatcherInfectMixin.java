package com.yayuy.overgrownvegetation.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.client.infect.InfectRender;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 汎用の感染体(2026-11-15)を描く間だけ、絵の差し替え({@link InfectRender})を有効にする。
 * どの描き方(バニラ・GeckoLib・ほかの MOD)もここから呼ばれるので、種ごとの手当てが要らない。炎・影はこの外。
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherInfectMixin {

    // エンティティごと・毎フレーム。感染体がいなければ表を 1 回引くだけ
    @WrapOperation(method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;render(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"),
            require = 0)
    private void overgrown_vegetation$infectScope(EntityRenderer<?> renderer, Entity entity, float yaw, float partialTick, PoseStack poseStack,
                                                  MultiBufferSource buffers, int packedLight, Operation<Void> original,
                                                  @Local(argsOnly = true, ordinal = 0) double camRelX,
                                                  @Local(argsOnly = true, ordinal = 1) double camRelY,
                                                  @Local(argsOnly = true, ordinal = 2) double camRelZ) {
        if ((com.yayuy.overgrownvegetation.client.crowd.CrowdRenderDev.skipDraw || com.yayuy.overgrownvegetation.client.crowd.CrowdRenderDev.sink)
                && renderer instanceof software.bernie.geckolib.renderer.GeoEntityRenderer<?> geo
                && geo.getGeoModel() instanceof com.yayuy.overgrownvegetation.client.LodGeoModel<?>) {
            // 確かめ用(大群の描画の重さの切り分け)。ふだんは通らない
            if (!com.yayuy.overgrownvegetation.client.crowd.CrowdRenderDev.skipDraw) {
                original.call(renderer, entity, yaw, partialTick, poseStack, com.yayuy.overgrownvegetation.client.crowd.CrowdRenderDev.SINK, packedLight);
            }
            return;
        }
        MultiBufferSource use = InfectRender.begin(entity, camRelX, camRelY, camRelZ, partialTick, poseStack, buffers);
        try {
            original.call(renderer, entity, yaw, partialTick, poseStack, use, packedLight);
        } finally {
            InfectRender.end(entity, poseStack, packedLight);
        }
    }
}
