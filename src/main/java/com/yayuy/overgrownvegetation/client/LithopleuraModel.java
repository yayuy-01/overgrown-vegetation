package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.LithopleuraEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;

/**
 * 「リトプレウラ」のモデル定義。
 *
 * <h2>{@code kara} は脱皮中だけ出す</h2>
 * <p>モデルには殻({@code kara} と子の {@code bone7}〜{@code bone11})が体と<b>重なって</b>入っている。
 * 常に描くと体が二重に見えるので、<b>普段は隠す</b>(ユーザー指定 2026-09-21)。
 *
 * <p>隠すのは {@link #setCustomAnimations} —— {@code preRender} ではここでやること。
 * {@link GeoBone#setHidden} は<b>子の表示可否も一緒に落とす</b>ので、
 * {@code kara} 一本を切り替えれば殻 5 節ぶん全部が付いてくる。
 *
 * <p>テクスチャは抜け殻ブロック({@code LithopleuraHuskModel})と共用。
 * 脱いだ殻と脱ぐ前の殻が同じ絵になるので、「さっきのあれだ」と分かる。
 */
public class LithopleuraModel extends LodGeoModel<LithopleuraEntity> {

    /** 殻のまとめ役。ここを隠すと子(bone7〜bone11)も消える。 */
    private static final String BONE_HUSK = "kara";

    @Override
    public ResourceLocation getModelResource(LithopleuraEntity animatable) {
        return OvergrownVegetation.id("geo/lithopleura.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(LithopleuraEntity animatable) {
        return OvergrownVegetation.id("textures/entity/lithopleura.png");
    }

    @Override
    public ResourceLocation getAnimationResource(LithopleuraEntity animatable) {
        return OvergrownVegetation.id("animations/lithopleura.animation.json");
    }

    @Override
    public void setCustomAnimations(LithopleuraEntity animatable, long instanceId,
                                    AnimationState<LithopleuraEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        GeoBone husk = getAnimationProcessor().getBone(BONE_HUSK);
        if (husk != null) {
            husk.setHidden(!animatable.isMolting());
        }
    }
}
