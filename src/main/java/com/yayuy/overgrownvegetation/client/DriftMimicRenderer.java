package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.drift.DriftMimicEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 流れ木もどきの描画(形 B 改 6・モデル制作の正本。11-22)。
 *
 * <ul>
 *   <li>当たり(= 胞子嚢の塊)は本体の位置の真ん中にある。形の塊は体の真ん中より {@link DriftMimicEntity#MODEL_BACK} 前にあるので、
 *       形をその分だけ後ろへずらす。形の y 0(水面)が水面に来るよう、当たりの沈み 0.2 だけ上げる。</li>
 *   <li>光: 発光マスク {@code drift_mimic_glowmask} を {@link DriftMimicEntity#glow} の強さで重ねる(正体を見せる・つかむ・破裂の前)。</li>
 *   <li>死ぬときにバニラの横倒しはしない(death のアニメで傾いて沈む。沈む移動は本体)。影は水面に落とさない。</li>
 * </ul>
 */
public class DriftMimicRenderer extends GeoEntityRenderer<DriftMimicEntity> {

    /** 当たりの底が水面より沈む深さ(本体の SUBMERGE と同じ)。 */
    static final float SUBMERGE = 0.2F;

    public DriftMimicRenderer(EntityRendererProvider.Context context) {
        super(context, new DriftMimicModel());
        this.shadowRadius = 0.0F;
        addRenderLayer(new MaskGlowLayer<>(this, MaskGlowLayer.mask("drift_mimic_glowmask"), DriftMimicEntity::glow));
    }

    @Override
    protected void applyRotations(DriftMimicEntity animatable, PoseStack poseStack, float ageInTicks,
                                  float rotationYaw, float partialTick, float nativeScale) {
        super.applyRotations(animatable, poseStack, ageInTicks, rotationYaw, partialTick, nativeScale);
        // 回した後の形の向きで: 後ろ(形の +z)へ塊の分だけ、上へ沈みの分だけ
        poseStack.translate(0.0F, SUBMERGE, (float) DriftMimicEntity.MODEL_BACK);
    }

    @Override
    protected float getDeathMaxRotation(DriftMimicEntity animatable) {
        return 0.0F;
    }
}
