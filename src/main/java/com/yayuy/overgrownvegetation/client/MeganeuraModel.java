package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.client.critter.CritterMotion;
import com.yayuy.overgrownvegetation.client.critter.CritterRig;
import software.bernie.geckolib.animation.AnimationState;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.MeganeuraEntity;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

public class MeganeuraModel extends LodGeoModel<MeganeuraEntity> {

    // 2026-09-24 軽量化(監査 1318): 描画のたびに ResourceLocation を作り直さないよう定数にする(中身は同じ)。
    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/meganeura.geo.json");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/meganeura.animation.json");

    @Override
    public ResourceLocation getModelResource(MeganeuraEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(MeganeuraEntity animatable) {
        return MobVariantTextures.of(animatable, "meganeura"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(MeganeuraEntity animatable) {
        return ANIMATION;
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

    /** 動きの層(注視・坂と旋回で傾く・尾と体節が道をなぞる・触角の遅れ・脚。G2・G3 共通、2026-10-22)。 */
    private static final String[] LEGS = {"leg", "leg2", "leg3", "leg4", "leg5", "leg6"};

    static final CritterRig RIG = CritterRig.create()
            .look(30, 20, "bone", 1.0F)
            .body("root", 0.6F)
            .climb(45, 30)
            .roll("root", 1.8F, 35)
            .flatChain(0.7F, 0.7F, "bone5", "bone6", "bone7", "bone8");

    @Override
    public void setCustomAnimations(MeganeuraEntity animatable, long instanceId, AnimationState<MeganeuraEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        CritterMotion.apply(this, animatable, animationState, RIG);
        // 飛んでいる間は 6 本の脚を後ろへたたむ(速いほど深く。止まって休む・食べる間は下ろしたまま)。
        // 骨の X − = 足先が後ろへ(中の値。BoneMath の説明)
        float tuck = Mth.clamp(CritterMotion.speed(animatable, animationState.getPartialTick()) * 6.0F, 0.0F, 1.0F) * 55.0F * Mth.DEG_TO_RAD;
        for (String leg : LEGS) {
            GeoBone b = getAnimationProcessor().getBone(leg);
            if (b != null && tuck > 0.0F) {
                b.setRotX(b.getRotX() - tuck);
                b.resetStateChanges();
            }
        }
    }
}
