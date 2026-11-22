package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import com.yayuy.overgrownvegetation.entity.g1.G1Developed;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1Move;
import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * G1 の 1 段目の、キーのアニメの後に重ねる手続きの骨(2026-11-13、能力の第 3 回)。
 *
 * <ul>
 *   <li><b>クリーパーの空の莢</b>: 爆ぜた後、背の莢(骨 pods・pods_tip)を、アニメ burst の終わりの姿(pods 0.55 × 0.47 × 0.55・pods_tip 0.52 と
 *   20° / 8° の垂れ)から、60 秒かけて元の大きさへ育ち直させる(仕様 6 節「regrow: 60 秒の間は手続き」)。背のシダ(crest_fern)は pods の子なので
 *   一緒に縮む【決定: アニメの直し】。満ち具合は {@link DevelopedParasitized#podsShown}。</li>
 *   <li><b>据えたまま射るスケルトン</b>: shoot_* のアニメは足の根(anchor_l / anchor_r)と沈み(root)のキーを持たないので、据えている間に射ると
 *   根が抜けて見える。据えていて shoot_* を流している間だけ、anchored_idle と同じ値(根 ±100°・root −0.36)を書く。</li>
 * </ul>
 * 骨はモデルごとの使い回しなので、書いた骨は必ず {@link GeoBone#resetStateChanges()}(呼ばないと次のフレームに戻らず積み上がる。
 * 記憶 geckolib-custom-anim-and-easing-traps)。
 */
final class G1DevelopedRig {

    /** 空の莢の姿(アニメ burst の終わりのキー)。 */
    private static final float PODS_X = 0.55F, PODS_Y = 0.47F, PODS_Z = 0.55F;
    private static final float TIP_SCALE = 0.52F;
    private static final float TIP_ROT_X = 20.0F, TIP_ROT_Z = 8.0F;
    /** 据えた足の根の角度(度)と体の沈み(アニメ anchored_idle のキー)。 */
    private static final float ANCHOR_ROT_Z = 100.0F;
    private static final float ANCHOR_SINK = -0.36F;

    private G1DevelopedRig() {
    }

    /**
     * evolve_in の始まりを立ち姿からつなぐ長さ(tick)【仮】。11-13 ユーザー「始まりを立ち姿に寄せてほしい」: 段階 0 は震えの終わりに立っているのに、
     * evolve_in の 0 秒目は前かがみ(ゾンビは胴 28°)で、入れ替わった 1 コマで姿勢が飛んでいた。アニメは直さず、始まりの間だけ、
     * どの骨も回転と位置を「モデルの静止の姿(立ち姿)→ アニメの値」へならす。拡大(植物が小さい所から育つ)はアニメのまま。
     */
    private static final float EVOLVE_BLEND_TICKS = 12.0F;

    private static void blendEvolveStart(GeoModel<?> model, DevelopedParasitized mob, AnimationState<?> state) {
        float t = mob.evolveClientTicks(state.getPartialTick());
        if (t >= EVOLVE_BLEND_TICKS) return;
        float k = Mth.clamp(t / EVOLVE_BLEND_TICKS, 0.0F, 1.0F);
        k = k * k * (3.0F - 2.0F * k);
        for (software.bernie.geckolib.cache.object.GeoBone bone : model.getAnimationProcessor().getRegisteredBones()) {
            software.bernie.geckolib.animation.state.BoneSnapshot init = bone.getInitialSnapshot();
            bone.setRotX(Mth.lerp(k, init.getRotX(), bone.getRotX()));
            bone.setRotY(Mth.lerp(k, init.getRotY(), bone.getRotY()));
            bone.setRotZ(Mth.lerp(k, init.getRotZ(), bone.getRotZ()));
            bone.setPosX(Mth.lerp(k, init.getOffsetX(), bone.getPosX()));
            bone.setPosY(Mth.lerp(k, init.getOffsetY(), bone.getPosY()));
            bone.setPosZ(Mth.lerp(k, init.getOffsetZ(), bone.getPosZ()));
            bone.resetStateChanges();
        }
    }

    static void apply(GeoModel<?> model, DevelopedParasitized mob, AnimationState<?> state) {
        // evolve_in の始まりのならし(blendEvolveStart)は 11-13 夜から呼ばない: アニメの側で同じ式がキーに焼かれた
        // (developed_parasitized_<種>.animation.json の evolve_in。0 秒目はどの骨も回転・位置 0)。二重に掛けないよう外した。
        // メソッドは、アニメを前の版へ戻したときのために残してある
        if (mob.dev() == G1Developed.CREEPER) {
            float empty = 1.0F - mob.podsShown(state.getPartialTick());
            if (empty > 0.001F) {
                GeoBone pods = model.getAnimationProcessor().getBone("pods");
                if (pods != null) {
                    pods.setScaleX(pods.getScaleX() * Mth.lerp(empty, 1.0F, PODS_X));
                    pods.setScaleY(pods.getScaleY() * Mth.lerp(empty, 1.0F, PODS_Y));
                    pods.setScaleZ(pods.getScaleZ() * Mth.lerp(empty, 1.0F, PODS_Z));
                    pods.resetStateChanges();
                }
                GeoBone tip = model.getAnimationProcessor().getBone("pods_tip");
                if (tip != null) {
                    float s = Mth.lerp(empty, 1.0F, TIP_SCALE);
                    tip.setScaleX(tip.getScaleX() * s);
                    tip.setScaleY(tip.getScaleY() * s);
                    tip.setScaleZ(tip.getScaleZ() * s);
                    // GeckoLib はアニメの回転の X・Y を符号を反して持つ
                    tip.setRotX(tip.getRotX() - empty * TIP_ROT_X * Mth.DEG_TO_RAD);
                    tip.setRotZ(tip.getRotZ() + empty * TIP_ROT_Z * Mth.DEG_TO_RAD);
                    tip.resetStateChanges();
                }
            }
        } else if (mob.dev() == G1Developed.SKELETON && mob.anchored()) {
            int a = mob.action();
            if (a == G1Move.SHOOT_WINDUP.id() || a == G1Move.SHOOT.id() || a == G1Move.SHOOT_RECOVER.id()) {
                GeoBone l = model.getAnimationProcessor().getBone("anchor_l");
                GeoBone r = model.getAnimationProcessor().getBone("anchor_r");
                GeoBone root = model.getAnimationProcessor().getBone("root");
                if (l != null) {
                    l.setRotZ(ANCHOR_ROT_Z * Mth.DEG_TO_RAD);
                    l.resetStateChanges();
                }
                if (r != null) {
                    r.setRotZ(-ANCHOR_ROT_Z * Mth.DEG_TO_RAD);
                    r.resetStateChanges();
                }
                if (root != null) {
                    root.setPosY(ANCHOR_SINK);
                    root.resetStateChanges();
                }
            }
        }
    }
}
