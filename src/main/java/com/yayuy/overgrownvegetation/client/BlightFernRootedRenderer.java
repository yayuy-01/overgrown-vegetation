package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.BlightFernRootedBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * 根付いた萌芽体の描画(2026-10-23 萌芽体のモデル)。歩く萌芽体と<b>同じ模型・絵・アニメのファイル</b>を使い、
 * 根付いた姿のアニメ(unfurl → rooted_idle)を流す。向きは歩いていたときの体の向き({@link BlightFernRootedBlockEntity#yaw})で、
 * GeckoLib の Mob の描画と同じ回し方(180 − 向き)にして、Mob → ブロックの入れ替わりで姿が跳ばないようにする。
 */
public class BlightFernRootedRenderer extends GeoBlockRenderer<BlightFernRootedBlockEntity> {

    /** 描く最大距離(ブロック)。仮決め。1 チャンクに 1〜2 株なので Mob(clientTrackingRange 12 チャンク)より短くてよい。 */
    private static final int VIEW_DISTANCE = 64;

    public BlightFernRootedRenderer() {
        super(new Model());
    }

    @Override
    protected void rotateBlock(Direction facing, PoseStack poseStack) {
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - this.animatable.yaw()));
    }

    @Override
    public int getViewDistance() {
        return VIEW_DISTANCE;
    }

    /** 模型は背丈 3.9・横に 1.7 ブロック広がる(開いた葉)。ブロック 1 つの箱のままだと、少し外れただけで描かれなくなる。 */
    // 1.20.1: 描画の箱は描き手でなく BlockEntity の getRenderBoundingBox()(G1 の持ち物)が持つ。ここは G1 から呼んでもらう
    public AABB getRenderBoundingBox(BlightFernRootedBlockEntity blockEntity) {
        return new AABB(blockEntity.getBlockPos()).inflate(1.8D, 0.0D, 1.8D).expandTowards(0.0D, 3.2D, 0.0D);
    }

    static final class Model extends GeoModel<BlightFernRootedBlockEntity> {
        private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/blight_fern.geo.json");
        private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/blight_fern.png");
        private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/blight_fern.animation.json");

        @Override
        public ResourceLocation getModelResource(BlightFernRootedBlockEntity animatable) {
            return MODEL;
        }

        @Override
        public ResourceLocation getTextureResource(BlightFernRootedBlockEntity animatable) {
            return TEXTURE;
        }

        @Override
        public ResourceLocation getAnimationResource(BlightFernRootedBlockEntity animatable) {
            return ANIMATION;
        }
    }
}
