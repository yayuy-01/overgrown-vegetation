package com.yayuy.overgrownvegetation.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yayuy.overgrownvegetation.client.corpse.ClientCorpseCollapse;
import com.yayuy.overgrownvegetation.client.corpse.CollapsePoser;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.entity.layers.EnergySwirlLayer;
import net.minecraft.client.renderer.entity.layers.SaddleLayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * 自分のモデルで setupAnim し直すレイヤーのうち、豚の鞍(SaddleLayer)と帯電したクリーパー(EnergySwirlLayer)も、
 * 崩れ落ちの最中は死体の姿勢へ寄せる(2026-10-09)。描き終えたら戻す(モデルは種で共有)。羊の毛は {@link RenderLayerCollapseMixin}。
 */
@Mixin({SaddleLayer.class, EnergySwirlLayer.class})
public abstract class ExtraLayerCollapseMixin {

    @Unique
    private static Map<ModelPart, PartPose> overgrown_vegetation$saved;

    @WrapOperation(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/Entity;FFFFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V"))
    private void overgrown_vegetation$collapseLayer(EntityModel<Entity> model, Entity entity, float limbSwing, float limbSwingAmount,
                                                    float ageInTicks, float netHeadYaw, float headPitch, Operation<Void> original) {
        original.call(model, entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
        if (entity instanceof LivingEntity living) {
            ClientCorpseCollapse.Collapse c = ClientCorpseCollapse.get(living);
            if (c != null) {
                float pt = net.minecraft.client.Minecraft.getInstance().getPartialTick();
                overgrown_vegetation$saved = CollapsePoser.applyParts(model, c, CollapsePoser.progress(living, pt));
            }
        }
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/Entity;FFFFFF)V",
            at = @At("RETURN"))
    private void overgrown_vegetation$restoreLayer(CallbackInfo ci) {
        if (overgrown_vegetation$saved != null) {
            CollapsePoser.restore(overgrown_vegetation$saved);
            overgrown_vegetation$saved = null;
        }
    }
}
