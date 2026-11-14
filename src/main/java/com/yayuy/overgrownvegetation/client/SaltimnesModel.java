package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.SaltimnesEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「サルティムネス」のモデル。正本は Blockbench の {@code デスクトップ\blockbench\mobs\saltimnes.bbmodel}(11-14 に練習モデル
 * {@code tools/g5-models/out/unadopted/practice/prac2_g5_leaper_r6} を写した。ユーザーが一覧の絵を見て「このままでよい」とした形)。
 *
 * <p>コードが足すもの(Blockbench には出ない): アニメ {@code leap} は骨 {@code root} の位置で体ごと前へ 40 px・上へ 22 px 動くが、
 * ゲームでは体そのもの(エンティティ)が弧を飛ぶので、{@code root} の位置をいつも 0 に戻す(root の位置のキーは leap にしか無い)。
 */
public class SaltimnesModel extends GeoModel<SaltimnesEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/saltimnes.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/saltimnes.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/saltimnes.animation.json");

    @Override
    public ResourceLocation getModelResource(SaltimnesEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(SaltimnesEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(SaltimnesEntity animatable) {
        return ANIMATION;
    }

    @Override
    public void setCustomAnimations(SaltimnesEntity animatable, long instanceId, AnimationState<SaltimnesEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        GeoBone root = getAnimationProcessor().getBone("root");
        if (root != null) {
            root.setPosX(0.0F);
            root.setPosY(0.0F);
            root.setPosZ(0.0F);
        }
    }
}
