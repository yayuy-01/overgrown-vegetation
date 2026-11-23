package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.stilt.StiltNeck;
import net.minecraft.util.Mth;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * 竹馬型の首の描画(2026-11-14、段 2。種によらない)。{@link StiltNeck} で解いた形を、キーのアニメの値と効き {@code w} で混ぜて骨へ書く。
 * 入れ子の伸びは neck_2〜4 の位置(Y)を<b>絶対値</b>で書く(idle_disguise などが −11 をキーしている)。胴を前へ倒す角は body の X 回りに足す。
 * 書いた骨は全部 {@link GeoBone#resetStateChanges()}(記憶 geckolib-custom-anim-and-easing-traps)。
 */
public final class StiltNeckRig {

    private StiltNeckRig() {
    }

    /**
     * @param neck   首の骨 4 つ(付け根から)
     * @param head   頭の骨
     * @param pose   解いた首(null なら首はアニメのまま。倒す角だけ書く)
     * @param leanDeg 胴を前へ倒す角(度)
     */
    public static void apply(GeoModel<?> model, StiltNeck.Shape shape, String body, String[] neck, String head,
                             StiltNeck.Pose pose, float w, double leanDeg) {
        GeoBone b = (software.bernie.geckolib.cache.object.GeoBone) model.getAnimationProcessor().getBone(body);
        if (b != null && Math.abs(leanDeg) > 1.0E-3D) {
            b.setRotX(b.getRotX() - (float) Math.toRadians(leanDeg));
            b.resetStateChanges();
        }
        if (pose == null || w <= 0.0F) return;
        double[] q = pose.qEuler();
        float off = (float) (-shape.telescope() * (1.0D - pose.s()));
        for (int i = 0; i < neck.length; i++) {
            GeoBone n = (software.bernie.geckolib.cache.object.GeoBone) model.getAnimationProcessor().getBone(neck[i]);
            if (n == null) continue;
            n.setRotX(Mth.lerp(w, n.getRotX(), (float) q[0]));
            n.setRotY(Mth.lerp(w, n.getRotY(), (float) q[1]));
            n.setRotZ(Mth.lerp(w, n.getRotZ(), (float) q[2]));
            if (i > 0) n.setPosY(Mth.lerp(w, n.getPosY(), off));
            n.resetStateChanges();
        }
        GeoBone h = (software.bernie.geckolib.cache.object.GeoBone) model.getAnimationProcessor().getBone(head);
        if (h != null) {
            double[] e = pose.headEuler();
            h.setRotX(Mth.lerp(w, h.getRotX(), (float) e[0]));
            h.setRotY(Mth.lerp(w, h.getRotY(), (float) e[1]));
            h.setRotZ(Mth.lerp(w, h.getRotZ(), (float) e[2]));
            h.resetStateChanges();
        }
    }
}
