package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.PhyllosensEntity;
import com.yayuy.overgrownvegetation.entity.PhyllosensWatch;
import com.yayuy.overgrownvegetation.entity.tower.TowerNeck;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * 監視塔フィロセンスのモデル(2026-11-14)。正本は Blockbench の {@code デスクトップ\blockbench\mobs\phyllosens_tower.bbmodel}。
 * geo・アニメ・絵・発光マスクは書き出し {@code tools/g5-models/out/phyllosens_tower_r2/} の写し(名前だけ phyllosens)。描画倍率なし。
 *
 * <p>コードが足すもの(Blockbench には出ない。骨の名前を変えると黙って効かなくなる):
 * <ul>
 *   <li>覗きの首: neck_1〜10 と head を、サーバーが選んだ頭の置き場へ解いた首({@link TowerNeck})へ、効き(0〜1)でまぜる。
 *   面の向きは neck_1 の Y 回りだけ(肩で振る)。</li>
 *   <li>見張りで相手を見つめる: neck_3〜8 の Y 回り(ねじり)を相手の向きの 1/6 ずつ、頭を相手へ(アニメの見回しと同じ動かし方)。</li>
 *   <li>折れた板根 stump_f1〜4 を隠す。閉じている・倒れている・死んだ間は芯 core を隠す(アニメの倍率 0 と同じ区間の保険)。</li>
 * </ul>
 * 角度はファイルの空間(アニメの json と同じ符号)で計算し、GeckoLib の骨へは x・y の符号を返して書く(記憶 geckolib-geo-mirror-yaw)。
 */
public class PhyllosensModel extends GeoModel<PhyllosensEntity> {
    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/phyllosens.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/phyllosens.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/phyllosens.animation.json");

    static final String[] NECK = {"neck_1", "neck_2", "neck_3", "neck_4", "neck_5", "neck_6", "neck_7", "neck_8", "neck_9", "neck_10"};
    static final String[] STUMPS = {"stump_f1", "stump_f2", "stump_f3", "stump_f4"};

    @Override
    public ResourceLocation getModelResource(PhyllosensEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(PhyllosensEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(PhyllosensEntity animatable) {
        return ANIMATION;
    }

    @Override
    public void setCustomAnimations(PhyllosensEntity e, long instanceId, AnimationState<PhyllosensEntity> animationState) {
        super.setCustomAnimations(e, instanceId, animationState);
        float pt = animationState.getPartialTick();
        int broken = e.clientBrokenMask();
        for (int i = 0; i < 4; i++) {
            GeoBone b = (software.bernie.geckolib.cache.object.GeoBone) getAnimationProcessor().getBone(STUMPS[i]);
            if (b != null) b.setHidden((broken & (1 << i)) != 0);
        }
        PhyllosensWatch.State st = e.isDeadOrDying() ? PhyllosensWatch.State.DEAD : e.state();
        GeoBone core = (software.bernie.geckolib.cache.object.GeoBone) getAnimationProcessor().getBone("core");
        if (core != null) {
            core.setHidden(switch (st) {
                case CLOSED, COLLAPSE, STUN, RISE, DEAD -> true;
                default -> false;
            });
        }
        if (e.isDeadOrDying()) return;
        Vec3 pos = e.position();
        float yaw = e.getYRot();
        double[] lookF = TowerNeck.toFile(pos.add(e.lookOffset()), pos, yaw);
        // 見張りで見つめる(ねじり)
        float lw = e.lockWeight(pt);
        if (lw > 0.0F) {
            float ly = e.lockYaw(pt);
            for (int i = 2; i <= 7; i++) {
                GeoBone b = bone(NECK[i]);
                if (b == null) continue;
                b.setRotY(Mth.lerp(lw, b.getRotY(), (float) -Math.toRadians(ly / 6.0D)));
                b.resetStateChanges();
            }
            TowerNeck.Pose wp = TowerNeck.of(TowerNeck.PHYLLOSENS, TowerNeck.WATCH, ly);
            aimHead(wp, lookF, lw);
        }
        // 覗きの首
        float w = e.peekWeight(pt);
        TowerNeck.Pose p = e.watch().clientPose();
        if (w > 0.0F && p != null) {
            for (int i = 0; i < NECK.length; i++) {
                GeoBone b = bone(NECK[i]);
                if (b == null) continue;
                b.setRotX(Mth.lerp(w, b.getRotX(), (float) -Math.toRadians(p.bend(i))));
                b.setRotY(Mth.lerp(w, b.getRotY(), i == 0 ? (float) -Math.toRadians(p.yaw()) : 0.0F));
                b.setRotZ(Mth.lerp(w, b.getRotZ(), 0.0F));
                b.resetStateChanges();
            }
            aimHead(p, lookF, w);
        }
    }

    private GeoBone bone(String name) {
        return (software.bernie.geckolib.cache.object.GeoBone) getAnimationProcessor().getBone(name);
    }

    /** 頭の花(−Z)を見る点へ向ける。 */
    private void aimHead(TowerNeck.Pose p, double[] lookF, float w) {
        GeoBone h = bone("head");
        if (h == null) return;
        double[] hp = TowerNeck.planeToFile(TowerNeck.PHYLLOSENS, p.tipR(), p.tipY(), p.yaw());
        double[] dir = {lookF[0] - hp[0], lookF[1] - hp[1], lookF[2] - hp[2]};
        double[] r = TowerNeck.headRotation(p, dir);
        h.setRotX(Mth.lerp(w, h.getRotX(), (float) -Math.toRadians(r[0])));
        h.setRotY(Mth.lerp(w, h.getRotY(), (float) -Math.toRadians(r[1])));
        h.setRotZ(Mth.lerp(w, h.getRotZ(), 0.0F));
        h.resetStateChanges();
    }
}
