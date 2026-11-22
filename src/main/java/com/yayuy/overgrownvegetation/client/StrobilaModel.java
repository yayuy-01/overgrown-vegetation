package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.StrobilaEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * 「ストロビラ」のモデル。正本は Blockbench の {@code デスクトップ\blockbench\mobs\strobila.bbmodel}(11-15 に練習モデル
 * {@code tools/g5-models/out/unadopted/practice/prac2_g4_lobber_r9} を写した。中の名前を strobila にしただけ)。
 * アニメは段 2(11-15、モデル制作): walk・run(脚は IK で焼いたキー)と投げの 3 本。体の傾き・段のならしは描画({@link com.yayuy.overgrownvegetation.client.crawler.SmallCrawlerRenderer})。
 */
public class StrobilaModel extends LodGeoModel<StrobilaEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/strobila.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/strobila.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/strobila.animation.json");

    @Override
    public ResourceLocation getModelResource(StrobilaEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(StrobilaEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(StrobilaEntity animatable) {
        return ANIMATION;
    }

    private static final boolean DEV_HOOKS = Boolean.getBoolean("ov.devbridge");
    private long lastTraceTick = Long.MIN_VALUE;

    /** テストクライアント用: 名前に trace を含む個体が投げている間、tick ごとに球果の骨の大きさ・尾の付け根の角度・飛んでいる弾の数を書く(絵と弾の時刻を比べる)。 */
    @Override
    public void setCustomAnimations(StrobilaEntity animatable, long instanceId,
                                    software.bernie.geckolib.core.animation.AnimationState<StrobilaEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        if (!DEV_HOOKS || !animatable.isThrowing() || !animatable.hasCustomName() || !animatable.getCustomName().getString().contains("trace")) return;
        long t = animatable.level().getGameTime();
        if (t == this.lastTraceTick) return;
        this.lastTraceTick = t;
        var cone = getAnimationProcessor().getBone("cone");
        var tail = getAnimationProcessor().getBone("tail0");
        var tail1 = getAnimationProcessor().getBone("tail1");
        int flying = animatable.level().getEntitiesOfClass(com.yayuy.overgrownvegetation.entity.StrobilaCone.class,
                animatable.getBoundingBox().inflate(6.0D)).size();
        OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT, "STROB_BONE gt %d 球果の大きさ %.2f 尾0 %.1f 度 尾1 y %.1f 度 弾 %d",
                t, cone == null ? -1.0F : cone.getScaleX(), tail == null ? 0.0F : Math.toDegrees(tail.getRotX()),
                tail1 == null ? 0.0F : Math.toDegrees(tail1.getRotY()), flying));
    }
}
