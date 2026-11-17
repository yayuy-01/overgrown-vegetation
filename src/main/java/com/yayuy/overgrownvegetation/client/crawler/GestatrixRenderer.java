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
        super(context, new GestatrixModel(), GestatrixEntity.RENDER_SCALE, 1.0F);
    }

    @Override
    public void preRender(PoseStack poseStack, GestatrixEntity animatable, BakedGeoModel model, MultiBufferSource bufferSource, VertexConsumer buffer,
                          boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
        int left = animatable.sacs();
        for (int i = 0; i < SACS.length; i++) {
            boolean hidden = i < SACS.length - left;
            model.getBone(SACS[i]).ifPresent(b -> b.setHidden(hidden));
        }
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);
    }
}
