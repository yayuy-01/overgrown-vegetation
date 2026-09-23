package com.yayuy.overgrownvegetation.worldgen.grammar;

/**
 * double[3] ベクトル演算。ブラウザ側 {@code src/turtle.js} の {@code SG.V} と一致させる。
 * 三角関数は {@link StrictMath}(fdlibn 由来)を使い、V8 の {@code Math.*} とビット一致を狙う。
 * Minecraft 非依存。
 */
final class GrammarVec {

    private GrammarVec() {
    }

    static double[] add(double[] a, double[] b) {
        return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    static double[] scale(double[] a, double s) {
        return new double[] {a[0] * s, a[1] * s, a[2] * s};
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static double[] cross(double[] a, double[] b) {
        return new double[] {
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        };
    }

    static double len(double[] a) {
        return Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
    }

    static double[] norm(double[] a) {
        double l = len(a);
        if (l < 1e-12) {
            return new double[] {0, 1, 0};
        }
        return new double[] {a[0] / l, a[1] / l, a[2] / l};
    }

    /** 単位軸 k まわりに角 rad 回した v(Rodrigues)。 */
    static double[] rotate(double[] v, double[] k, double rad) {
        double c = StrictMath.cos(rad);
        double s = StrictMath.sin(rad);
        double[] kv = cross(k, v);
        double kd = dot(k, v) * (1 - c);
        return new double[] {
            v[0] * c + kv[0] * s + k[0] * kd,
            v[1] * c + kv[1] * s + k[1] * kd,
            v[2] * c + kv[2] * s + k[2] * kd
        };
    }

    static double[] slerp(double[] a, double[] b, double t) {
        a = norm(a);
        b = norm(b);
        double d = Math.min(Math.max(dot(a, b), -1), 1);
        double ang = StrictMath.acos(d);
        if (ang < 1e-6) {
            return new double[] {a[0], a[1], a[2]};
        }
        double sinA = StrictMath.sin(ang);
        double w1 = StrictMath.sin((1 - t) * ang) / sinA;
        double w2 = StrictMath.sin(t * ang) / sinA;
        return norm(new double[] {
            a[0] * w1 + b[0] * w2,
            a[1] * w1 + b[1] * w2,
            a[2] * w1 + b[2] * w2
        });
    }
}
