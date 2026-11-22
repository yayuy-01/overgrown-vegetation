package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.SaltimnesEntity;
import com.yayuy.overgrownvegetation.entity.leap.GroundFit;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「サルティムネス」のモデル。正本は Blockbench の {@code デスクトップ\blockbench\mobs\saltimnes.bbmodel}(11-14 に練習モデル r6 から起こし、
 * 11-15 段 2 で walk の直しと跳びの分けたアニメ・技のアニメを足した。アニメはモデル制作の持ち場で、ここでは変えない)。
 *
 * <p>コードが足すもの(Blockbench には出ない。骨の名前を変えると黙って効かなくなる):
 * <ul>
 *   <li>根 {@code root}: 地面の起伏に合わせた体の前後・左右の傾きと上げ下げ({@link GroundFit})、宙では弧の向きへの前後の傾き。</li>
 *   <li>歩く 4 本の付け根 {@code mid0_l・mid0_r・hind0_l・hind0_r} の Z 回し: 傾けた体の下で足が地面からずれる量だけ、その脚を上げ下げする
 *       (足先までの横の腕の長さ 中脚 8.5 px・後脚 5.5 px で角度にする。足の運びはアニメのまま)。</li>
 * </ul>
 * 向き(GeckoLib の中の値): rotX + で前(−Z)が上がる、rotZ + で右(+X)が上がる。
 */
public class SaltimnesModel extends GeoModel<SaltimnesEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/saltimnes.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/saltimnes.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/saltimnes.animation.json");

    /** 脚の付け根の骨({@link SaltimnesEntity#FEET} と同じ順: 左中・右中・左後・右後)と、付け根から足先までの横の長さ(px)。 */
    private static final String[] LEG_ROOT = {"mid0_l", "mid0_r", "hind0_l", "hind0_r"};
    private static final double[] LEG_LEVER = {8.5D, 8.5D, 5.5D, 5.5D};
    /** 脚の上げ下げの上限(ラジアン)。 */
    private static final double LEG_MAX = Math.toRadians(45.0D);

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
    public void setCustomAnimations(SaltimnesEntity e, long instanceId, AnimationState<SaltimnesEntity> animationState) {
        super.setCustomAnimations(e, instanceId, animationState);
        float pt = animationState.getPartialTick();
        GroundFit g = e.groundFit();
        double pxPerBlock = 16.0D / SaltimnesEntity.RENDER_SCALE / Math.max(e.getScale(), 0.05F);
        GeoBone root = getAnimationProcessor().getBone("root");
        if (root != null) {
            root.setRotX(root.getRotX() + (float) (g.pitch(pt) + e.airPitch(pt)));
            root.setRotZ(root.getRotZ() - (float) g.roll(pt));
            root.setPosY(root.getPosY() + (float) (g.lift(pt) * pxPerBlock));
            root.resetStateChanges();   // キーの無い骨は、下ろさないと足した値が次のフレームにも残って積み上がる
        }
        for (int i = 0; i < LEG_ROOT.length; i++) {
            GeoBone b = getAnimationProcessor().getBone(LEG_ROOT[i]);
            if (b == null) continue;
            double err = g.footError(i, pt) * pxPerBlock;
            if (Math.abs(err) < 1.0E-3D) continue;
            double a = Math.max(-LEG_MAX, Math.min(LEG_MAX, Math.atan2(err, LEG_LEVER[i])));
            boolean right = SaltimnesEntity.FEET[i][0] > 0.0D;
            b.setRotZ(b.getRotZ() + (float) (right ? a : -a));
            b.resetStateChanges();
        }
    }
}
