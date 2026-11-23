package com.yayuy.overgrownvegetation.client.runner;

import com.yayuy.overgrownvegetation.entity.runner.LongBodyRunner;
import net.minecraft.util.Mth;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * 長い体の曲げを骨に足す、種に依らない描画(2026-11-14、{@code FalcimnesBendRig} から書き起こした。クライアントの描画だけ)。
 * 角度はサーバーが求めて送ったもの({@code entity/runner/BodyBend}。前 4 つは先の経路、後ろ 4 つは通った跡に沿う。体に対する向き、左が +)。
 * 骨はそれぞれ親に対して回るので、隣の付け根との差を足す。
 *
 * <p>継ぎ目の箱(親と子のつなぎ目を埋める骨。子の回転の半分で置いてある)は、その子に足した曲げの半分を足す(足さないと継ぎ目が開く)。
 */
public final class BodyBendRig {

    /** 前の付け根 4 つの骨(首から頭へ)と継ぎ目の骨(無ければ null)。 */
    private final String[] front, frontSeam;
    /** 後ろの付け根 4 つの骨(尾の付け根から先へ)と継ぎ目の骨。 */
    private final String[] back, backSeam;

    private final float[] a = new float[8];

    public BodyBendRig(String[] front, String[] frontSeam, String[] back, String[] backSeam) {
        this.front = front;
        this.frontSeam = frontSeam;
        this.back = back;
        this.backSeam = backSeam;
    }

    public void apply(GeoModel<?> model, LongBodyRunner e, float pt) {
        if (!e.bend().bent() && !e.bend().wasBent()) return;
        e.bend().angles(pt, this.a);
        float before = 0.0F;
        for (int k = 0; k < 4; k++) {
            float d = this.a[k] - before;
            yaw(model, this.front[k], d, true);
            if (this.frontSeam[k] != null) yaw(model, this.frontSeam[k], d * 0.5F, true);
            before = this.a[k];
        }
        before = 0.0F;
        for (int k = 0; k < 4; k++) {
            float d = this.a[4 + k] - before;
            yaw(model, this.back[k], d, false);
            if (this.backSeam[k] != null) yaw(model, this.backSeam[k], d * 0.5F, false);
            before = this.a[4 + k];
        }
    }

    /** 体に対して左へ {@code deg} 度(前の骨か後ろの骨かで回す向きが逆。ファルキムネスで確かめた向き)。 */
    private static void yaw(GeoModel<?> model, String name, float deg, boolean front) {
        if (deg == 0.0F || name == null) return;
        GeoBone b = (software.bernie.geckolib.cache.object.GeoBone) model.getAnimationProcessor().getBone(name);
        if (b == null) return;
        b.setRotY(b.getRotY() + (front ? 1.0F : -1.0F) * deg * Mth.DEG_TO_RAD);
        b.resetStateChanges();   // アニメにキーの無い骨は、消さないと回転が積み重なる
    }
}
