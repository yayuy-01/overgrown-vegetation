package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.FodimnesPart;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.animation.state.BoneSnapshot;

/**
 * フォディムネスの<b>節</b>({@code fodimnes_segment})と<b>尾</b>({@code fodimnes_tail})のモデル定義。
 * 部位の約束(体の軸 Z・前 −Z・断面の中心 (0, 4.3, 0)・つなぎ目 ±6.4)は {@code tools/g5-models/out/fodimnes/NOTES.md}。
 *
 * <p>本体({@link LodGeoModel})の遠くの「アニメを止める」判定を<b>本体で</b>引き、止めるときは骨を静止の姿勢へ戻す
 * (部位はワールドに入っていないので、部位そのものでは引けない。戻し方は LodGeoModel と同じ理由 —— 骨はモデルごとに 1 組で使い回し)。
 */
public class FodimnesPartModel extends GeoModel<FodimnesPart> {

    private final ResourceLocation model;
    private final ResourceLocation texture;
    private final ResourceLocation animation;

    /** @param id {@code fodimnes_segment} か {@code fodimnes_tail} */
    public FodimnesPartModel(String id) {
        this.model = OvergrownVegetation.id("geo/" + id + ".geo.json");
        this.texture = OvergrownVegetation.id("textures/entity/" + id + ".png");
        this.animation = OvergrownVegetation.id("animations/" + id + ".animation.json");
    }

    @Override
    public ResourceLocation getModelResource(FodimnesPart animatable) {
        return this.model;
    }

    @Override
    public ResourceLocation getTextureResource(FodimnesPart animatable) {
        return this.texture;
    }

    @Override
    public ResourceLocation getAnimationResource(FodimnesPart animatable) {
        return this.animation;
    }

    @Override
    public void handleAnimations(FodimnesPart animatable, long instanceId, AnimationState<FodimnesPart> animationState, float partialTick) {
        if (MobRenderLod.shouldStopAnimation(animatable.getParent())) {
            for (GeoBone bone : getAnimationProcessor().getRegisteredBones()) {
                BoneSnapshot rest = bone.getInitialSnapshot();
                bone.updateRotation(rest.getRotX(), rest.getRotY(), rest.getRotZ());
                bone.updatePosition(rest.getOffsetX(), rest.getOffsetY(), rest.getOffsetZ());
                bone.updateScale(rest.getScaleX(), rest.getScaleY(), rest.getScaleZ());
            }
            return;
        }
        super.handleAnimations(animatable, instanceId, animationState, partialTick);
    }
}
