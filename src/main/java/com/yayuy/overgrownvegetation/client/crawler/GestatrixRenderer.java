package com.yayuy.overgrownvegetation.client.crawler;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.entity.GestatrixEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.cache.object.BakedGeoModel;

/**
 * ゲスタトリクスの描画(2026-11-17)。背の嚢 {@code sac_0}〜{@code sac_3}(0 がいちばん上・産む順に 0 から)を、残りの嚢の数
 * ({@link GestatrixEntity#sacs()}。産む瞬間 = birth の tick 8 に減る)に合わせて隠す。残り n なら {@code sac_(4-n)}〜{@code sac_3} を見せる
 * (骨の名前はモデル制作の一覧「ゲスタトリクス_骨とアニメの名前」)。仮のモデル(グレガリア)には骨が無いので何もしない。死の回転は 0(土台の型と同じ)。
 */
public class GestatrixRenderer extends SmallCrawlerRenderer<GestatrixEntity> {

    private static final String[] SACS = {"sac_0", "sac_1", "sac_2", "sac_3"};

    public GestatrixRenderer(EntityRendererProvider.Context context) {
        super(context, new GestatrixModel(), GestatrixEntity.RENDER_SCALE, 1.4F);
        // 目はいつも光る・前触れの間だけ背の嚢が光る(素材はモデル制作 11-17 夜)
        this.addRenderLayer(new com.yayuy.overgrownvegetation.client.MaskGlowLayer<>(this, com.yayuy.overgrownvegetation.client.MaskGlowLayer.mask("gestatrix_glowmask"), GestatrixEntity::eyeGlow));
        this.addRenderLayer(new com.yayuy.overgrownvegetation.client.MaskGlowLayer<>(this, com.yayuy.overgrownvegetation.client.MaskGlowLayer.mask("gestatrix_glow_sac"), GestatrixEntity::sacGlow));
    }

    @Override
    public void preRender(PoseStack poseStack, GestatrixEntity animatable, BakedGeoModel model, MultiBufferSource bufferSource, VertexConsumer buffer,
                          boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
        int left = animatable.sacs();
        for (int i = 0; i < SACS.length; i++) {
            boolean hidden = i < SACS.length - left;
            model.getBone(SACS[i]).ifPresent(b -> b.setHidden(hidden));
        }
        // 根付いた後の芽・嚢(sprout_1・hatch_1)は、根付くまで隠す(クリップの外では見えてしまう。モデル制作 11-17 夜の頼み)
        boolean rooted = animatable.rooted();
        for (int i = 1; i <= GestatrixEntity.SPROUTS; i++) model.getBone("sprout_" + i).ifPresent(b -> b.setHidden(!rooted));
        for (int i = 1; i <= GestatrixEntity.PODS; i++) model.getBone("hatch_" + i).ifPresent(b -> b.setHidden(!rooted));
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);
    }
}
