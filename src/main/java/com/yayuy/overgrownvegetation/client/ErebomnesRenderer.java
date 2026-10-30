package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.entity.ErebomnesEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

import javax.annotation.Nullable;

/**
 * 「エレボムネス」の描画。目のレンズ 4 粒と P 板の点 2 つの光({@code erebomnes_glowmask.png})を重ねる。
 * 描画倍率 2 は mobs.json の {@code scale}(SCALE 属性)で掛かる(GeckoLib が {@code getScale()} を掛ける)。
 *
 * <ul>
 *     <li>死んだら目の光を消す(NOTES: 低キューブ版はレンズを目の玉の面に描いたので骨で消せない)。死んでから
 *     {@link ErebomnesEntity#DEATH_GLOW_OFF_TICKS}(death の 2.3 秒 = 床に横たわった所 + 補間)で光の層を描かない。</li>
 *     <li>影の半径 2(NOTES の目安)。宙に浮いているので、床から離れると薄くなる(バニラの影の決まり)。</li>
 *     <li>視錐台の打ち切りは実体の {@code getBoundingBoxForCulling} で広げてある(頭・尾扇が当たり判定の外へ出る)。</li>
 * </ul>
 */
public class ErebomnesRenderer extends GeoEntityRenderer<ErebomnesEntity> {

    public ErebomnesRenderer(EntityRendererProvider.Context context) {
        super(context, new ErebomnesModel());
        this.shadowRadius = 2.0F;
        addRenderLayer(new EyeGlowLayer(this));
    }

    /** 死の動き(death)はアニメが持っているので、バニラの横転を切る(既定の 90° と重なると平たい体が鰭の縁で立つ。NOTES)。 */
    @Override
    protected float getDeathMaxRotation(ErebomnesEntity animatable) {
        return 0.0F;
    }

    /** 発光マスクの層。死んで床に横たわったら描かない(目の光が消える)。 */
    private static final class EyeGlowLayer extends AutoGlowingGeoLayer<ErebomnesEntity> {
        EyeGlowLayer(GeoRenderer<ErebomnesEntity> renderer) {
            super(renderer);
        }

        @Override
        public void render(PoseStack poseStack, ErebomnesEntity animatable, BakedGeoModel bakedModel, @Nullable RenderType renderType,
                           MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
            if (animatable.deathTime >= ErebomnesEntity.DEATH_GLOW_OFF_TICKS) {
                return;
            }
            super.render(poseStack, animatable, bakedModel, renderType, bufferSource, buffer, partialTick, packedLight, packedOverlay);
        }
    }
}
