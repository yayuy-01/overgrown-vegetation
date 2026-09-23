package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.entity.SiegeBombEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/** 攻城戦ボスが落とす爆弾の描画。落下中はゆっくり回転する。 */
public class SiegeBombRenderer extends EntityRenderer<SiegeBombEntity> {

    private static final ResourceLocation TEXTURE =
            OvergrownVegetation.id("textures/entity/siege_bomb.png");

    private final SiegeBombModel model;

    public SiegeBombRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.model = new SiegeBombModel(context.bakeLayer(OvergrownVegetationModelLayers.SIEGE_BOMB));
        this.shadowRadius = 0.6F;
    }

    @Override
    public ResourceLocation getTextureLocation(SiegeBombEntity entity) {
        return TEXTURE;
    }

    @Override
    public void render(SiegeBombEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        float scale = OvergrownVegetationConfig.COMMON.siegeBombScale.get().floatValue();
        float age = entity.tickCount + partialTick;

        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(entity.spinSeed() + age * 4.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(age * 1.5F));
        poseStack.scale(-scale, -scale, scale);

        VertexConsumer buffer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        this.model.render(poseStack, buffer, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        poseStack.popPose();

        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }
}
