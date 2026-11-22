package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

import javax.annotation.Nullable;
import java.util.function.BiFunction;

/**
 * 行動のときだけ光る層(2026-11-16 コードの見直し ①14 で 7 種の写しをまとめた: ストロビラ・ヴェシカリア・セミナリア・クリペイムネス・
 * サルティムネス・ピリムネス・ヴェナムネス)。発光マスク 1 枚(透明の上に光る画素だけ)を光る描き方でもう一度重ね、
 * 不透明さを {@code brightness}(0〜1。0 なら描かない)にする。マスクが 2 枚の種は層を 2 つ足す。
 *
 * <p>GeckoLib の {@code AutoGlowingGeoLayer} は常に光る・光る画素 0 の絵で落ちるので使わない(ファルキムネスの鎌の筋と同じ作り)。
 * 絵がまだ MOD に無いときは描かない(無い絵は紫と黒の格子になり、それが全身に光って重なる)。有るかは層を作り直す
 * (資源の読み直し)ごとに 1 回だけ見る。</p>
 */
public class MaskGlowLayer<T extends GeoAnimatable> extends GeoRenderLayer<T> {

    private final ResourceLocation mask;
    private final BiFunction<T, Float, Float> brightness;
    @Nullable
    private Boolean hasMask;

    public MaskGlowLayer(GeoRenderer<T> renderer, ResourceLocation mask, BiFunction<T, Float, Float> brightness) {
        super(renderer);
        this.mask = mask;
        this.brightness = brightness;
    }

    /** 発光マスクの場所({@code textures/entity/<name>.png})。 */
    public static ResourceLocation mask(String name) {
        return OvergrownVegetation.id("textures/entity/" + name + ".png");
    }

    @Override
    public void render(PoseStack poseStack, T animatable, BakedGeoModel bakedModel, @Nullable RenderType renderType,
                       MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
        float glow = this.brightness.apply(animatable, partialTick);
        if (glow <= 0.01F) return;
        if (this.hasMask == null) {
            this.hasMask = Minecraft.getInstance().getResourceManager().getResource(this.mask).isPresent();
            if (!this.hasMask) OvergrownVegetation.LOGGER.info("発光マスクがまだ無いので光らせない: {}", this.mask);
        }
        if (!this.hasMask) return;
        RenderType rt = RenderType.entityTranslucentEmissive(this.mask);
        int a = Math.round(Math.min(1.0F, glow) * 255.0F);
        getRenderer().reRender(bakedModel, poseStack, bufferSource, animatable, rt, bufferSource.getBuffer(rt), partialTick,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, FastColor.ARGB32.color(a, 255, 255, 255));
    }
}
