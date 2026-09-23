package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.MeganeuraEntity;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class MeganeuraModel extends LodGeoModel<MeganeuraEntity> {

    @Override
    public ResourceLocation getModelResource(MeganeuraEntity animatable) {
        return OvergrownVegetation.id("geo/meganeura.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(MeganeuraEntity animatable) {
        return MobVariantTextures.of(animatable, "meganeura"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(MeganeuraEntity animatable) {
        return OvergrownVegetation.id("animations/meganeura.animation.json");
    }

    /**
     * 羽を半透明で描くための描画タイプ。
     *
     * <p>GeckoLib の既定は {@code RenderType.entityCutoutNoCull} で、これはアルファを
     * 「抜くか抜かないか」でしか扱えない(閾値以上は全部不透明)ため、テクスチャの羽の部分に
     * 半端なアルファ(α=135 / 199)を塗っても真っ白に潰れて不透明になってしまう。
     * {@code entityTranslucent} に変えるとアルファがそのままブレンドされる。
     * {@code entityTranslucent} は背面カリングもしないので、厚さ0の羽ポリゴンは裏からも見える。
     */
    @Override
    public RenderType getRenderType(MeganeuraEntity animatable, ResourceLocation texture) {
        return RenderType.entityTranslucent(texture);
    }
}
