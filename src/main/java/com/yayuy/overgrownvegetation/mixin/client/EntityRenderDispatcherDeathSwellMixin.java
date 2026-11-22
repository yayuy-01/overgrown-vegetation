package com.yayuy.overgrownvegetation.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.client.PlantDeathSwell;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 植物側の死の破裂の膨らみ({@link PlantDeathSwell}、2026-11-15)。描き手を呼ぶ所を包み、足元を原点に震えと膨らみをかけ、
 * 描く間だけ {@code deathTime}・{@code hurtTime} を 0 にする(バニラ・GeckoLib の横倒しと赤い被弾色を止める)。
 * 汎用の感染体の絵の差し替え({@code EntityRenderDispatcherInfectMixin})も同じ呼び出しを包むが、WrapOperation どうしは鎖になるので両方効く。
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherDeathSwellMixin {

    @WrapOperation(method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;render(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"),
            require = 0)
    private void overgrown_vegetation$deathSwell(EntityRenderer<?> renderer, Entity entity, float yaw, float partialTick, PoseStack poseStack,
                                                 MultiBufferSource buffers, int packedLight, Operation<Void> original) {
        if (!(entity instanceof LivingEntity living) || living.deathTime <= 0 || !PlantDeathSwell.applies(living)) {
            original.call(renderer, entity, yaw, partialTick, poseStack, buffers, packedLight);
            return;
        }
        int deathTime = living.deathTime, hurtTime = living.hurtTime;
        poseStack.pushPose();
        try {
            PlantDeathSwell.apply(poseStack, living, partialTick);
            living.deathTime = 0;
            living.hurtTime = 0;
            original.call(renderer, entity, yaw, partialTick, poseStack, buffers, packedLight);
        } finally {
            living.deathTime = deathTime;
            living.hurtTime = hurtTime;
            poseStack.popPose();
        }
    }
}
