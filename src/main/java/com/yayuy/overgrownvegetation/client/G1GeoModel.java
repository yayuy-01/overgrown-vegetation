package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import software.bernie.geckolib.animatable.GeoAnimatable;

/**
 * G1 の感染体のモデル(2026-10-09 の作り直し)。geo・アニメ・絵は {@code <id>.geo.json}・{@code <id>.animation.json}・
 * {@code textures/entity/<id>.png}。どれも生成器 {@code tools/g5-models/mobs/G1*.java} の生成物(2026-11-08 から作り直し 2 回目の版を {@code tools/g5-models/g1/install_v2.sh} で写す。亜種の絵は無い)。
 */
public class G1GeoModel<T extends Entity & GeoAnimatable> extends LodGeoModel<T> {

    private final String id;
    private final ResourceLocation model;
    private final ResourceLocation animation;
    private final ResourceLocation texture;
    /** 半透明で描くか(寄生されたガスト・ブレイズの胞子嚢。ペディキュラと同じ半透明の絵、2026-10-09)。 */
    private final boolean translucent;

    public G1GeoModel(String id) {
        this(id, false);
    }

    public G1GeoModel(String id, boolean translucent) {
        this.id = id;
        this.translucent = translucent;
        this.model = OvergrownVegetation.id("geo/" + id + ".geo.json");
        this.animation = OvergrownVegetation.id("animations/" + id + ".animation.json");
        this.texture = OvergrownVegetation.id("textures/entity/" + id + ".png");
    }

    @Override
    public ResourceLocation getModelResource(T animatable) {
        return model;
    }

    @Override
    public ResourceLocation getTextureResource(T animatable) {
        return texture;
    }

    @Override
    public ResourceLocation getAnimationResource(T animatable) {
        return animation;
    }

    /** 1 段目の手続きの骨(クリーパーの空の莢・据えたまま射るスケルトンの足の根。2026-11-13)。キーのアニメの後に重ねる。 */
    @Override
    public void setCustomAnimations(T animatable, long instanceId, software.bernie.geckolib.animation.AnimationState<T> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        if (animatable instanceof com.yayuy.overgrownvegetation.entity.DevelopedParasitized d) {
            G1DevelopedRig.apply(this, d, animationState);
        }
    }

    /** 胞子嚢のある種は半透明(GeckoLib の既定の cutout だと半透明の画素が不透明の塊になる。PediculaModel と同じ)。 */
    @Override
    public net.minecraft.client.renderer.RenderType getRenderType(T animatable, ResourceLocation texture) {
        return this.translucent ? net.minecraft.client.renderer.RenderType.entityTranslucent(texture)
                : super.getRenderType(animatable, texture);
    }
}
