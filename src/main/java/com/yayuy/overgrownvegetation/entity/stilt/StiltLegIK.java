package com.yayuy.overgrownvegetation.entity.stilt;

/**
 * 竹馬型の脚 1 本の IK(2026-11-10。種によらない。描画は {@code client/StiltLegRig}、検算は GameTest)。GeckoLib の型を使わない純粋な計算。
 *
 * <p>脚の骨は 付け根(coxa)→ 腿(femur)→ 脛(tibia)→ 足先(tarsus)。どれも<b>静止の回転 0</b>で、箱だけが次の関節へ傾けてある
 * (r2 の生成器の作り)。だから骨の静止の向き = 関節の pivot から次の pivot への線。
 * <ul>
 *   <li>付け根は動かさない。</li>
 *   <li>足先は真下へ向ける(下 = 付け根の座標での「下」)。足先の付け根 = 置き場の点 + 足先の長さ × 上。</li>
 *   <li>腿と脛は 2 本の IK。膝は「付け根 → 足首の線」の<b>後ろ</b>(中の座標で +Z)・少し外・少し上へ出す(決定 11-05: 膝は後ろへ折れる)。
 *       かがんで脚が縮むほど外・上へ開く。</li>
 *   <li>骨ごとの回転は、静止の向きから求める向きへの最短の回り(ねじれを足さない)。GeckoLib の回転(Rz·Ry·Rx)の角度に直す。</li>
 * </ul>
 * 座標は GeckoLib の中の座標(モデル単位 1/16 ブロック。前 = −Z・右 = +X・上 = +Y)で、付け根の骨(coxa)の親の空間。
 */
public final class StiltLegIK {

    private StiltLegIK() {
    }

    /**
     * 解く。femur・tibia・tarsus は各骨の pivot、tip は静止の足先、target は置きたい足先、down は下の向き(単位)。
     * 返り値は {femur の x, y, z, tibia の x, y, z, tarsus の x, y, z}(ラジアン)と、届かなかった量(モデル単位)を [9] に。
     */
    public static double[] solve(double[] femur, double[] tibia, double[] tarsus, double[] tip, double[] target, double[] down) {
        double la = dist(femur, tibia), lb = dist(tibia, tarsus), lc = dist(tarsus, tip);
        // 足首(足先の付け根)の目標
        double[] ankle = {target[0] - down[0] * lc, target[1] - down[1] * lc, target[2] - down[2] * lc};
        double[] fa = sub(ankle, femur);
        double d = len(fa);
        double short_ = 0.0D;
        double dMax = la + lb - 0.01D, dMin = Math.abs(la - lb) + 0.01D;
        if (d > dMax) {
            short_ = d - dMax;
            d = dMax;
        } else if (d < dMin) {
            d = dMin;
        }
        double[] u = len(fa) < 1.0E-6D ? new double[]{0, -1, 0} : scale(fa, 1.0D / len(fa));
        ankle = add(femur, scale(u, d));
        // 膝の向き(後ろ・少し外・少し上)。脚が縮むほど外へ開く(決定 11-10「低くかがむと膝が胴より高く外へ張り出す」)
        double out = femur[0] >= 0.0D ? 1.0D : -1.0D;
        double fold = Math.max(0.0D, 0.85D - d / (la + lb));
        double[] pole = {out * (0.35D + 2.0D * fold), 0.25D + 3.5D * fold, 1.0D - 0.6D * fold};
        double[] w = sub(pole, scale(u, dot(pole, u)));
        if (len(w) < 1.0E-6D) w = new double[]{0, 0, 1};
        w = scale(w, 1.0D / len(w));
        double a = (la * la - lb * lb + d * d) / (2.0D * d);
        double hh = Math.sqrt(Math.max(0.0D, la * la - a * a));
        double[] knee = add(femur, add(scale(u, a), scale(w, hh)));
        // 腿
        double[] r1 = arc(unit(sub(tibia, femur)), unit(sub(knee, femur)));
        // 脛(腿の中で)
        double[] r1t = transpose(r1);
        double[] r2 = arc(unit(sub(tarsus, tibia)), mul(r1t, unit(sub(ankle, knee))));
        // 足先(脛の中で)
        double[] r12t = transpose(matmul(r1, r2));
        double[] r3 = arc(unit(sub(tip, tarsus)), mul(r12t, down));
        double[] e1 = euler(r1), e2 = euler(r2), e3 = euler(r3);
        return new double[]{e1[0], e1[1], e1[2], e2[0], e2[1], e2[2], e3[0], e3[1], e3[2], short_};
    }

    /** 回転の行列(3×3、行優先)を GeckoLib の角度 (x, y, z)(M = Rz·Ry·Rx)に。 */
    public static double[] euler(double[] m) {
        double r20 = m[6], r21 = m[7], r22 = m[8], r10 = m[3], r00 = m[0];
        double y = Math.asin(Math.max(-1.0D, Math.min(1.0D, -r20)));
        double x = Math.atan2(r21, r22), z = Math.atan2(r10, r00);
        if (Math.abs(r20) > 0.9999D) {
            // ほぼ真上・真下を向く(使わないはず): z を 0 に
            z = 0.0D;
            x = Math.atan2(-m[5], m[4]);
        }
        return new double[]{x, y, z};
    }

    /** GeckoLib の角度 → 行列(M = Rz·Ry·Rx)。検算用。 */
    public static double[] matrix(double x, double y, double z) {
        double cx = Math.cos(x), sx = Math.sin(x), cy = Math.cos(y), sy = Math.sin(y), cz = Math.cos(z), sz = Math.sin(z);
        return new double[]{
                cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx,
                sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx,
                -sy, cy * sx, cy * cx};
    }

    /** 単位ベクトル a を b へ回す最短の回り。 */
    static double[] arc(double[] a, double[] b) {
        double[] v = cross(a, b);
        double c = dot(a, b);
        if (c < -0.99999D) {
            // 真逆: a に垂直な軸で 180 度
            double[] axis = Math.abs(a[0]) < 0.9D ? cross(a, new double[]{1, 0, 0}) : cross(a, new double[]{0, 1, 0});
            axis = unit(axis);
            double x = axis[0], y = axis[1], z = axis[2];
            return new double[]{2 * x * x - 1, 2 * x * y, 2 * x * z, 2 * x * y, 2 * y * y - 1, 2 * y * z, 2 * x * z, 2 * y * z, 2 * z * z - 1};
        }
        double k = 1.0D / (1.0D + c);
        double vx = v[0], vy = v[1], vz = v[2];
        return new double[]{
                1 + k * (-vy * vy - vz * vz), -vz + k * vx * vy, vy + k * vx * vz,
                vz + k * vx * vy, 1 + k * (-vx * vx - vz * vz), -vx + k * vy * vz,
                -vy + k * vx * vz, vx + k * vy * vz, 1 + k * (-vx * vx - vy * vy)};
    }

    public static double[] mul(double[] m, double[] v) {
        return new double[]{m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2], m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
    }

    public static double[] matmul(double[] a, double[] b) {
        double[] o = new double[9];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                o[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
            }
        }
        return o;
    }

    static double[] transpose(double[] m) {
        return new double[]{m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]};
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    public static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    public static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    static double[] scale(double[] a, double s) {
        return new double[]{a[0] * s, a[1] * s, a[2] * s};
    }

    static double len(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    static double[] unit(double[] a) {
        double l = len(a);
        return l < 1.0E-9D ? new double[]{0, -1, 0} : scale(a, 1.0D / l);
    }

    public static double dist(double[] a, double[] b) {
        return len(sub(a, b));
    }

    /**
     * 解いた角度で、足先がどこへ来るか(検算用)。骨の回りは pivot まわり: 子の点 q → pivot + R·(q − pivot)、親から順に掛ける。
     */
    public static double[] forward(double[] femur, double[] tibia, double[] tarsus, double[] tip, double[] ang) {
        double[] r1 = matrix(ang[0], ang[1], ang[2]), r2 = matrix(ang[3], ang[4], ang[5]), r3 = matrix(ang[6], ang[7], ang[8]);
        // 足先の点を、足先の骨 → 脛 → 腿の順に回す
        double[] q = add(tarsus, mul(r3, sub(tip, tarsus)));
        q = add(tibia, mul(r2, sub(q, tibia)));
        q = add(femur, mul(r1, sub(q, femur)));
        return q;
    }
}
