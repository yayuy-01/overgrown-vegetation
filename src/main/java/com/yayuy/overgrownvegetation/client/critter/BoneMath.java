package com.yayuy.overgrownvegetation.client.critter;

import net.minecraft.util.Mth;
import software.bernie.geckolib.cache.object.GeoBone;

/**
 * GeckoLib の骨に「親の座標で見た回転」を足すための計算(2026-10-22、G2・G3 の動きの層)。
 *
 * <h2>なぜ角度を足すだけではいけないのか</h2>
 * <p>GeckoLib は骨の回転を {@code Rz · Ry · Rx}(頂点には X が先)で組む。静止の回転を持つ骨
 * (外へ開いた脚の板など)に Y を足すと、開く前の向きで回してから開くことになり、
 * 真下を向いた脚の板は自分の軸まわりに回るだけで前後へ振れない。
 * そこで「親の座標で回す Q」を今の行列 M の左から掛け({@code M' = Q · M})、オイラー角へ戻して書く。
 *
 * <h2>座標と符号(GeckoLib の中の値)</h2>
 * <p>GeckoLib は geo / animation の json の X・Y の回転の符号を反転して持つ(Z はそのまま。
 * {@code BakedModelFactory} と {@code BakedAnimationsAdapter} で確認)。この層の角度はすべて<b>中の値</b>で、
 * json(Blockbench)の意味に直すと次のとおり:
 * <ul>
 *   <li>X+ = 前(−Z)が<b>上がる</b>(json の X+ は前が下がる)</li>
 *   <li>Y+ = 前が生き物の<b>左</b>(json の +X)へ振れる</li>
 *   <li>Z+ = 生き物の左(json の +X)側が下がる(json と同じ)</li>
 * </ul>
 */
public final class BoneMath {

    // 描画のスレッドだけで使う作業用(毎フレーム何十本も回すので作り直さない)。
    private static final float[] M = new float[9];
    private static final float[] Q = new float[9];
    private static final float[] R = new float[9];

    private BoneMath() {
    }

    /** 骨の今の回転に、親の座標の軸 (ax, ay, az)(長さ 1)まわりの angle(ラジアン)を先に掛ける。 */
    public static void preRotate(GeoBone bone, float ax, float ay, float az, float angle) {
        if (angle == 0.0F) {
            return;
        }
        euler(bone.getRotX(), bone.getRotY(), bone.getRotZ(), M);
        axisAngle(ax, ay, az, angle, Q);
        mul(Q, M, R);
        write(bone, R);
    }

    /** 親の座標の Y 軸(縦)まわりに回す。 */
    public static void preRotateY(GeoBone bone, float angle) {
        preRotate(bone, 0.0F, 1.0F, 0.0F, angle);
    }

    /** 回転を足す(静止の回転の無い骨・素直な軸の骨はこちらで十分。オイラー角に足すだけ)。 */
    public static void add(GeoBone bone, float x, float y, float z) {
        if (x != 0.0F) {
            bone.setRotX(bone.getRotX() + x);
        }
        if (y != 0.0F) {
            bone.setRotY(bone.getRotY() + y);
        }
        if (z != 0.0F) {
            bone.setRotZ(bone.getRotZ() + z);
        }
    }

    // ---------------------------------------------------------------- 3×3 行列(行優先)

    /** M = Rz(z) · Ry(y) · Rx(x)。 */
    static void euler(float x, float y, float z, float[] out) {
        float cx = Mth.cos(x), sx = Mth.sin(x);
        float cy = Mth.cos(y), sy = Mth.sin(y);
        float cz = Mth.cos(z), sz = Mth.sin(z);
        out[0] = cz * cy;
        out[1] = cz * sy * sx - sz * cx;
        out[2] = cz * sy * cx + sz * sx;
        out[3] = sz * cy;
        out[4] = sz * sy * sx + cz * cx;
        out[5] = sz * sy * cx - cz * sx;
        out[6] = -sy;
        out[7] = cy * sx;
        out[8] = cy * cx;
    }

    static void axisAngle(float x, float y, float z, float a, float[] out) {
        float c = Mth.cos(a), s = Mth.sin(a), t = 1.0F - c;
        out[0] = t * x * x + c;
        out[1] = t * x * y - s * z;
        out[2] = t * x * z + s * y;
        out[3] = t * x * y + s * z;
        out[4] = t * y * y + c;
        out[5] = t * y * z - s * x;
        out[6] = t * x * z - s * y;
        out[7] = t * y * z + s * x;
        out[8] = t * z * z + c;
    }

    static void mul(float[] a, float[] b, float[] out) {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                out[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
            }
        }
    }

    /** 行列をオイラー角(Rz · Ry · Rx)に戻して骨へ書く。真上・真下を向く(ジンバルロック)ときは Z を 0 にする。 */
    static void write(GeoBone bone, float[] m) {
        float sy = -m[6];
        if (sy > 0.99999F || sy < -0.99999F) {
            float y = sy > 0 ? Mth.HALF_PI : -Mth.HALF_PI;
            float x = (float) Math.atan2(m[1] * sy, m[4]);
            bone.updateRotation(x, y, 0.0F);
            return;
        }
        float y = (float) Math.asin(Mth.clamp(sy, -1.0F, 1.0F));
        float x = (float) Math.atan2(m[7], m[8]);
        float z = (float) Math.atan2(m[3], m[0]);
        bone.updateRotation(x, y, z);
    }
}
