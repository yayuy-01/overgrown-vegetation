package com.yayuy.overgrownvegetation.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.client.corpse.ClientCorpseCollapse;
import com.yayuy.overgrownvegetation.client.corpse.CollapsePoser;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * 自分のモデルで setupAnim し直すレイヤー(羊の毛など、{@code coloredCutoutModelCopyLayerRender})も、
 * 崩れ落ちの最中は死体の姿勢へ寄せる(2026-10-09)。描き終えたら戻す。
 */
// 1.20.1: coloredCutoutModelCopyLayerRender の色は int 1 つではなく float 3 つ(赤・緑・青)。手続きの引数だけ合わせた
@Mixin(RenderLayer.class)
public abstract class RenderLayerCollapseMixin {

    @Unique
    private static Map<ModelPart, PartPose> overgrown_vegetation$saved;

    @Inject(method = "coloredCutoutModelCopyLayerRender", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V", shift = At.Shift.AFTER))
    private static <T extends LivingEntity> void overgrown_vegetation$collapseLayer(EntityModel<T> parent, EntityModel<T> model, ResourceLocation texture,
                                                                                    PoseStack poseStack, MultiBufferSource buffer, int packedLight, T entity,
                                                                                    float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
                                                                                    float headPitch, float partialTick, float red, float green, float blue, CallbackInfo ci) {
        ClientCorpseCollapse.Collapse c = ClientCorpseCollapse.get(entity);
        if (c != null) {
            overgrown_vegetation$saved = CollapsePoser.applyParts(model, c, CollapsePoser.progress(entity, partialTick));
        }
    }

    @Inject(method = "coloredCutoutModelCopyLayerRender", at = @At("RETURN"))
    private static <T extends LivingEntity> void overgrown_vegetation$restoreLayer(EntityModel<T> parent, EntityModel<T> model, ResourceLocation texture,
                                                                                   PoseStack poseStack, MultiBufferSource buffer, int packedLight, T entity,
                                                                                   float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
                                                                                   float headPitch, float partialTick, float red, float green, float blue, CallbackInfo ci) {
        if (overgrown_vegetation$saved != null) {
            CollapsePoser.restore(overgrown_vegetation$saved);
            overgrown_vegetation$saved = null;
        }
    }
}
