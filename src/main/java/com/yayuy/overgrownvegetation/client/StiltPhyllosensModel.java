package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.StiltPhyllosensEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「フィロセンス」(竹馬型)のモデル。正本は Blockbench の {@code デスクトップ\blockbench\mobs\phyllosens.bbmodel}(11-10 に
 * 生成器 {@code tools/g5-models/mobs/PhylloStiltR2.java} の出力から置いた)。geo・アニメ・絵はそこからの写し。
 *
 * <p>コードが足すもの(Blockbench には出ない): root を {@link StiltPhyllosensEntity#ROOT_SHIFT} 下げる(エンティティの位置 = 胴)、
 * 脚 4 本の IK({@link StiltLegRig}。キーの脚の値は上書き)、腺毛の先の粘液の玉 {@code tendril_N_dew} を隠す(絡めの間だけ見せる。段 1 は常に隠す)。
 * 骨の名前を変えるとこれらが黙って効かなくなる。
 */
public class StiltPhyllosensModel extends GeoModel<StiltPhyllosensEntity> {
    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/phyllosens_stilt.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/phyllosens_stilt.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/phyllosens_stilt.animation.json");

    static final String[][] LEGS = {
            {"leg_fl_coxa", "leg_fl_femur", "leg_fl_tibia", "leg_fl_tarsus"},
            {"leg_fr_coxa", "leg_fr_femur", "leg_fr_tibia", "leg_fr_tarsus"},
            {"leg_bl_coxa", "leg_bl_femur", "leg_bl_tibia", "leg_bl_tarsus"},
            {"leg_br_coxa", "leg_br_femur", "leg_br_tibia", "leg_br_tarsus"}};
    /**
     * 足先の骨の静止の先(中の座標、足先の pivot からのずれ、モデル単位)。geo の箱(長さ 8.124、回転 (79.975, ∓45 / ∓135, 0))から
     * GeckoLib の向き(x 反転・回転 −x −y・Rz·Ry·Rx)で求めた。左前 (−17, 8, −26) → (−18, 0, −27)。
     */
    static final double[][] TIPS = {{-1.0D, -8.0D, -1.0D}, {1.0D, -8.0D, -1.0D}, {-1.0D, -8.0D, 1.0D}, {1.0D, -8.0D, 1.0D}};
    static final String[] DEW = {"tendril_1_dew", "tendril_2_dew", "tendril_3_dew", "tendril_4_dew"};

    @Override
    public ResourceLocation getModelResource(StiltPhyllosensEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(StiltPhyllosensEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(StiltPhyllosensEntity animatable) {
        return ANIMATION;
    }

    @Override
    public void setCustomAnimations(StiltPhyllosensEntity animatable, long instanceId, AnimationState<StiltPhyllosensEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        for (String d : DEW) {
            GeoBone b = (software.bernie.geckolib.cache.object.GeoBone) getAnimationProcessor().getBone(d);
            if (b != null) b.setHidden(true);
        }
        float w = animatable.isDeadOrDying() ? 0.0F : 1.0F;
        StiltLegRig.apply(this, animatable, animatable.clientFeet(), LEGS, TIPS, StiltPhyllosensEntity.ROOT_SHIFT,
                animationState.getPartialTick(), w);
        // 段 2(11-14): 首と頭を手続きで(頭を置く点・見る点はサーバーが決めて送る)。胴を前へ倒す角も
        float pt = animationState.getPartialTick();
        var gz = animatable.gaze();
        float nw = gz.weight(pt) * w;
        net.minecraft.world.phys.Vec3 pos = new net.minecraft.world.phys.Vec3(
                net.minecraft.util.Mth.lerp(pt, animatable.xo, animatable.getX()),
                net.minecraft.util.Mth.lerp(pt, animatable.yo, animatable.getY()),
                net.minecraft.util.Mth.lerp(pt, animatable.zo, animatable.getZ()));
        float yaw = net.minecraft.util.Mth.rotLerp(pt, animatable.yBodyRotO, animatable.yBodyRot);
        StiltNeckRig.apply(this, gz.shape, "body", NECK, "head", nw > 0.0F ? gz.pose(pos, yaw, pt) : null, nw, gz.lean(pt) * w);
    }

    static final String[] NECK = {"neck_1", "neck_2", "neck_3", "neck_4"};
}
