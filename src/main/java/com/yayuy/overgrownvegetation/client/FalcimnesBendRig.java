package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.FalcimnesEntity;
import net.minecraft.util.Mth;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * ファルキムネスの体の曲げを骨に足す(11-08、森の中を動く型 ② の段 2。クライアントの描画だけ)。
 * 角度はサーバーが求めて送ったもの({@code entity/FalcimnesBend}。首 4 つは先の経路、腹 4 つは通った跡に沿う。体に対する向き、左が +)。
 * 骨はそれぞれ親に対して回るので、隣の骨との差を足す。翅は腹の付け根の上に乗っているので、腹の中ほどの向きの 6 割だけ振る【仮】。
 */
public final class FalcimnesBendRig {

    /**
     * ファイル座標の Y 回転で「左へ」になる向き(+1 / −1)。首(前 = −z)と腹(後ろ = +z)は逆に回す。
     * テストクライアントの名前 {@code bendtest} の個体(決まった曲げ)で、叩ける部位の箱と重ねて確かめた値。
     */
    static final float LEFT = 1.0F;
    static final float WING_SHARE = 0.6F;
    private static final boolean DEV = Boolean.getBoolean("ov.devbridge");

    private static final float[] A = new float[8];

    private FalcimnesBendRig() {
    }

    public static void apply(GeoModel<FalcimnesEntity> model, FalcimnesEntity e, AnimationState<FalcimnesEntity> state) {
        if (!e.bend().bent() && !e.bend().wasBent()) return;
        e.bend().angles(state.getPartialTick(), A);
        float n0 = A[0], n1 = A[1], n2 = A[2], n3 = A[3];
        float t = (2.798F - 1.474F) / (3.280F - 1.474F);
        float nb = Mth.lerp(t, n0, n1);
        yaw(model, "neck", n0, true);
        yaw(model, "bone", nb - n0, true);
        yaw(model, "bone2", n1 - nb, true);
        yaw(model, "bone3", n2 - n1, true);
        yaw(model, "head", n3 - n2, true);
        float b0 = A[4], b1 = A[5], b2 = A[6], b3 = A[7];
        yaw(model, "bone21", b0, false);
        // 腹の 1 節目(bone21)だけ、骨の回す中心(pivot z 20.7)が節の中ほどにあり、胸とのつなぎ目(z 5.25 = 箱の前の端)に無い。
        // そのまま横へ回すと節の前の端が胸から逆側へ振れて、つなぎ目が大きくずれて見えた(11-08 夜、ユーザー「羽が付いている部分から
        // 一つ後ろの節が、曲がるときにかなりずれている」)。位置をずらして、回す中心をつなぎ目へ移す: t = (R − I)(p − q)
        String dev = DEV && e.hasCustomName() ? e.getCustomName().getString() : "";
        if (!dev.equals("bendtest_old")) {
            shiftPivot(model, "bone21", b0, BONE21_JOINT_Z);
        }
        if (DEV) {   // 骨はモデルで共有なので、ほかの個体では毎回戻す
            // 確かめ用(テストクライアントだけ): 翅を隠して腹のつなぎ目を見る。bendtest_old は直す前(回す中心を移さない)
            for (String w : new String[]{"wing", "wing2"}) {
                GeoBone b = model.getAnimationProcessor().getBone(w);
                if (b != null) b.setHidden(dev.startsWith("bendtest"));
            }
        }
        yaw(model, "bone22", b1 - b0, false);
        yaw(model, "bone23", b2 - b1, false);
        yaw(model, "bone24", b3 - b2, false);
        yaw(model, "bone33", b3 - b2, false);
        yaw(model, "wing", b1 * WING_SHARE, false);
        yaw(model, "wing2", b1 * WING_SHARE, false);
    }

    /** 腹の 1 節目と胸のつなぎ目(ファイル座標の z、px。bone20 の箱の後ろの端 5.3 と bone21 の箱の前の端 5.2 の間)。 */
    static final float BONE21_JOINT_Z = 5.25F;

    /**
     * {@link #yaw} で後ろの骨を {@code deg} 回したあと、回す中心を pivot から z = {@code jointZ} へ移す位置のずらしを足す。
     * GeckoLib は Y 軸まわりを標準の右手系で回し(x' = x cos + z sin、z' = −x sin + z cos)、位置の X は描画で反転される(段 1 の
     * {@link FalcimnesLegRig} の胴と同じ扱い)。
     */
    private static void shiftPivot(GeoModel<FalcimnesEntity> model, String name, float deg, float jointZ) {
        if (deg == 0.0F) return;
        GeoBone b = model.getAnimationProcessor().getBone(name);
        if (b == null) return;
        float ry = -LEFT * deg * Mth.DEG_TO_RAD;   // yaw(…, false) で足した中の回転
        float d = b.getPivotZ() - jointZ;          // p − q(中の座標、z だけ)
        float tx = d * Mth.sin(ry), tz = d * (Mth.cos(ry) - 1.0F);
        b.setPosX(b.getPosX() - tx);
        b.setPosZ(b.getPosZ() + tz);
        // 11-08 夜: 「変えた」印を消す。消さないと次のフレームに GeckoLib が初めの位置へ戻さず、ずらしがフレームごとに積み重なって
        // 曲がるたびに体の後ろ半分が遠くへ飛んでいった(ユーザー報告「体の後ろ半分が変の方向に吹き飛ぶ」。メモリ geckolib-custom-anim-and-easing-traps)
        b.resetStateChanges();
    }

    /** 体に対して左へ {@code deg} 度(前の骨か後ろの骨かで回す向きが逆)。 */
    private static void yaw(GeoModel<FalcimnesEntity> model, String name, float deg, boolean front) {
        if (deg == 0.0F) return;
        GeoBone b = model.getAnimationProcessor().getBone(name);
        if (b == null) return;
        float sign = front ? LEFT : -LEFT;
        b.setRotY(b.getRotY() + sign * deg * Mth.DEG_TO_RAD);
        b.resetStateChanges();   // 同上(アニメにキーの無い骨は、消さないと回転も積み重なる)
    }
}
