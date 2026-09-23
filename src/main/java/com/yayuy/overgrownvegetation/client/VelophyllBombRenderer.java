package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.VelophyllBombEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * ヴェロフィルが落とす胞子爆弾の描画。
 *
 * <p>見た目は<b>攻城戦ボスの爆弾と同じ</b>({@link SiegeBombModel} とそのテクスチャをそのまま使う) —
 * 「胞子爆弾はティタニカと同じもの」というユーザー指定どおり。違うのは大きさと、爆ぜ方だけ
 * ({@link VelophyllBombEntity} 参照)。
 */
public class VelophyllBombRenderer extends EntityRenderer<VelophyllBombEntity> {

    private static final ResourceLocation TEXTURE =
            OvergrownVegetation.id("textures/entity/siege_bomb.png");

    /** 攻城戦ボスの爆弾より小ぶり。 */
    private static final float SCALE = 0.55F;

    private final SiegeBombModel model;

    public VelophyllBombRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.model = new SiegeBombModel(context.bakeLayer(OvergrownVegetationModelLayers.SIEGE_BOMB));
        this.shadowRadius = 0.35F;
    }

    @Override
    public ResourceLocation getTextureLocation(VelophyllBombEntity entity) {
        return TEXTURE;
    }

    @Override
    public void render(VelophyllBombEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        float age = entity.tickCount + partialTick;

        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(entity.spinSeed() + age * 6.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(age * 2.5F));
        // 機関砲台が胞子弾を撃ち落として散った破片は小さい({@code VelophyllBombEntity#size})。
        float scale = SCALE * entity.size();
        poseStack.scale(-scale, -scale, scale);

        VertexConsumer buffer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        this.model.render(poseStack, buffer, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        poseStack.popPose();

        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }
}
