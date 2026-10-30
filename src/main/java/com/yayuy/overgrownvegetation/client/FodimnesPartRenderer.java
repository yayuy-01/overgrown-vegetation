package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.entity.FodimnesEntity;
import com.yayuy.overgrownvegetation.entity.FodimnesPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoObjectRenderer;

import javax.annotation.Nullable;

/**
 * フォディムネスの節・尾を 1 つ描く小さな描き手({@link FodimnesRenderer} が部位の数だけ呼ぶ)。
 *
 * <ul>
 *   <li>{@link GeoObjectRenderer} はブロック向けに {@code preRender} で (0.5, 0.51, 0.5) ずらすので外す(位置・向き・倍率は呼ぶ側の PoseStack。
 *       {@code TitanicaImpostorRenderer} と同じ)。</li>
 *   <li>{@code GeoObjectRenderer#render} は受け取った {@code MultiBufferSource} を捨てて本体のものへ替えるので使わず、
 *       {@link #defaultRender} を直接呼ぶ(光る縁取り・別の出力先でも本体と同じ所へ描く)。</li>
 *   <li>被弾・倒れるときの赤みは本体の {@code hurtTime}・{@code deathTime}(体力は共通なので、どこを殴っても体じゅうが赤くなる)。</li>
 * </ul>
 */
public class FodimnesPartRenderer extends GeoObjectRenderer<FodimnesPart> {

    public FodimnesPartRenderer(FodimnesPartModel model) {
        super(model);
    }

    /** 部位 1 つを、呼ぶ側が合わせた PoseStack(部位の原点 = 地面の中心、向き・倍率込み)で描く。 */
    public void renderPart(PoseStack poseStack, FodimnesPart part, MultiBufferSource bufferSource, int packedLight, float partialTick) {
        this.animatable = part;
        defaultRender(poseStack, part, bufferSource, null, null, 0.0F, partialTick, packedLight);
    }

    @Override
    public void preRender(PoseStack poseStack, FodimnesPart animatable, BakedGeoModel model, @Nullable MultiBufferSource bufferSource,
                          @Nullable VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
        this.objectRenderTranslations = new Matrix4f(poseStack.last().pose());
    }

    @Override
    public int getPackedOverlay(FodimnesPart animatable, float u, float partialTick) {
        FodimnesEntity owner = animatable.getParent();
        return OverlayTexture.pack(OverlayTexture.u(u), OverlayTexture.v(owner.hurtTime > 0 || owner.deathTime > 0));
    }
}
