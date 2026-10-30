package com.yayuy.overgrownvegetation.entity;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * フォディムネスの部位の<b>向き</b>の計算(ユーザー指定「節の向きは方向ベクトルで持ち、描くときはクォータニオンで前の向きからの最小回転
 * (真上を向いてもねじれない)」)。ワールドに触らない静的な関数だけ(GameTest から直接呼ぶ)。描くのはクライアントだけ。
 *
 * <h2>約束</h2>
 * 向き(クォータニオン q)は<b>モデルの空間 → ワールド</b>。モデルは前が −Z・上が +Y・生き物の右が +X
 * ({@code GeoEntityRenderer} が {@code Y 180 − yaw} で回したときの向き。{@code tools/g5-models/out/fodimnes/NOTES.md} の「部位の約束」)。
 * <ul>
 *   <li>頭: 前の tick の向きから、今の tick の向きへの最小回転を掛けて運ぶ(時間の回転最小化)。真上・真下を向いて yaw が変わっても
 *       向きのベクトルが変わらなければ回らない —— Euler 角(yaw → pitch)で組むと、ここで頭がくるりと回ってしまう。
 *       水平に近いときだけ、背を上へ向けた姿勢({@link #upright})へ少しずつ戻す(長く使うと少しずつねじれが溜まるのを消す)。</li>
 *   <li>節・尾: 頭から尾へ、1 つ前の部位の向きから自分の向き(道の接線)への最小回転を掛けて運ぶ(道に沿った回転最小化フレーム)。</li>
 *   <li>ひねり(roll)は最後にモデルの Z 軸まわりに掛ける({@link #applyRoll})。正 = 右へ傾く(背が右へ)。</li>
 * </ul>
 */
public final class FodimnesFrames {

    private FodimnesFrames() {
    }

    /** モデルの前(−Z)。 */
    public static final Vector3f MODEL_FORWARD = new Vector3f(0.0F, 0.0F, -1.0F);
    /** モデルの上(+Y)。 */
    public static final Vector3f MODEL_UP = new Vector3f(0.0F, 1.0F, 0.0F);

    /** from → to の最小回転(どちらも単位ベクトル)。真逆のときは from に垂直な軸で 180 度。 */
    public static Quaternionf minimalRotation(Vector3f from, Vector3f to, Quaternionf dest) {
        float d = from.dot(to);
        if (d > 0.9999999F) {
            return dest.identity();
        }
        if (d < -0.9999F) {
            Vector3f axis = new Vector3f(1.0F, 0.0F, 0.0F).cross(from);
            if (axis.lengthSquared() < 1.0E-6F) {
                axis.set(0.0F, 1.0F, 0.0F).cross(from);
            }
            axis.normalize();
            return dest.rotationAxis((float) Math.PI, axis);
        }
        return dest.rotationTo(from, to);
    }

    /** 向き q を、前の向き prevDir から今の向き dir への最小回転で運ぶ(q は上書き)。 */
    public static Quaternionf transport(Quaternionf q, Vector3f prevDir, Vector3f dir) {
        Quaternionf r = minimalRotation(prevDir, dir, new Quaternionf());
        return r.mul(q, q);
    }

    /**
     * 背を上へ向けた姿勢(Minecraft の yaw・pitch、度)。{@code Y(180 − yaw) · X(−pitch)} —— pitch が正(下を向く)で前が下がる。
     * 前は {@code Vec3.directionFromRotation(pitch, yaw)} と同じ向きになる。
     */
    public static Quaternionf upright(float yawDeg, float pitchDeg, Quaternionf dest) {
        return dest.rotationY((float) Math.toRadians(180.0F - yawDeg)).rotateX((float) Math.toRadians(-pitchDeg));
    }

    /** 前 fwd・上のおおよその向き upHint から姿勢を組む(upHint は fwd と平行でないこと)。 */
    public static Quaternionf fromForwardUp(Vector3f fwd, Vector3f upHint, Quaternionf dest) {
        Vector3f f = new Vector3f(fwd).normalize();
        Vector3f right = new Vector3f(f).cross(upHint);
        if (right.lengthSquared() < 1.0E-8F) {
            right.set(1.0F, 0.0F, 0.0F);
        }
        right.normalize();
        Vector3f up = new Vector3f(right).cross(f).normalize();
        // 列 = モデルの +X(右)・+Y(上)・+Z(後ろ = −前)のワールドでの向き
        org.joml.Matrix3f m = new org.joml.Matrix3f(
                right.x, right.y, right.z,
                up.x, up.y, up.z,
                -f.x, -f.y, -f.z);
        return dest.setFromNormalized(m);
    }

    /**
     * 頭の向きを 1 tick 進める: 前の向き prevDir → 今の向き dir の最小回転で運び、水平に近いほど {@link #upright} へ少し寄せる
     * (寄せても前の向きは変わらない —— 2 つの姿勢は前が同じなので、差は前の軸まわりの回転だけ)。
     *
     * @param relax 1 tick に寄せる割合(水平のとき)。真上・真下では 0
     */
    public static Quaternionf advanceHead(Quaternionf q, Vector3f prevDir, Vector3f dir, float yawDeg, float pitchDeg, float relax) {
        transport(q, prevDir, dir);
        float horizontal = 1.0F - Math.abs(dir.y);
        if (horizontal > 1.0E-3F && relax > 0.0F) {
            Quaternionf up = upright(yawDeg, pitchDeg, new Quaternionf());
            // upright の前が dir と少しずれていても(yaw・pitch は量子化される)、前の軸まわりの差だけを取って寄せる
            Vector3f upFwd = up.transform(new Vector3f(MODEL_FORWARD));
            minimalRotation(upFwd, dir, new Quaternionf()).mul(up, up);
            q.slerp(up, relax * horizontal * horizontal);
        }
        return q.normalize();
    }

    /**
     * 頭の向き head から尾へ、部位ごとの向き(前 = 頭の方の単位ベクトル)dirs[i] に沿って回転最小化フレームを運ぶ。
     *
     * @param headDir 頭の前の向き
     * @param out     長さ dirs.length。部位ごとの向き(roll なし)
     */
    public static void propagate(Quaternionf head, Vector3f headDir, Vector3f[] dirs, Quaternionf[] out) {
        Quaternionf prev = head;
        Vector3f prevDir = headDir;
        for (int i = 0; i < dirs.length; i++) {
            Quaternionf q = out[i].set(prev);
            transport(q, prevDir, dirs[i]);
            q.normalize();
            prev = q;
            prevDir = dirs[i];
        }
    }

    /** ひねり(度、正 = 右へ傾く)をモデルの前の軸まわりに掛ける(q は上書き)。 */
    public static Quaternionf applyRoll(Quaternionf q, float rollDeg) {
        return q.rotateZ((float) Math.toRadians(-rollDeg));
    }

    /** 2 つの向きの間の角度(度)。 */
    public static float angleBetween(Quaternionf a, Quaternionf b) {
        Quaternionf d = new Quaternionf(a).conjugate().mul(b);
        float w = Math.min(1.0F, Math.abs(d.w));
        return (float) Math.toDegrees(2.0D * Math.acos(w));
    }
}
