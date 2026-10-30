package com.yayuy.overgrownvegetation.skyboss;

import java.util.ArrayList;
import java.util.List;

/**
 * 空の巨大生物の技の弾(2026-10-19。対空砲火の蕾の胞子の塊・背の腺の樹液の弾・腹の袋の種の爆弾)。エンティティにしない: ボスは y 950 の空にいて、
 * 弾はプレイヤーの読み込んだチャンクの外を何百ブロックも飛ぶ(エンティティはそこで止まる)。ボスと同じく<b>両側が同じ式で飛ばす</b>:
 * サーバーが撃った弾(撃った tick・位置・速さ)を {@code SkyBossShotPayload} で送り、クライアントは同じ {@link #move} で描く。
 * 当たり・炸裂はサーバーが決めて、終わった弾を送る(クライアントはそこで消す)。Minecraft のクラスを使わない(GameTest で素で流せる)。
 */
public final class SkyBossShots {

    public static final int SPORE = 0;
    public static final int SAP = 1;
    public static final int SEED = 2;

    /** 弾 1 つ。{@code px..pz} は一つ前の tick の位置(描画の補間・線分の当たり)。 */
    public static final class Shot {
        public int id;
        public int kind;
        public double x;
        public double y;
        public double z;
        public double px;
        public double py;
        public double pz;
        public double vx;
        public double vy;
        public double vz;
        public long born;
        public long tick;
        public int life;
        public boolean dead;
    }

    public final List<Shot> list = new ArrayList<>();

    /** 重さ(1 tick に下へ足す速さ、ブロック / tick²)。胞子の塊はまっすぐ、樹液は少し落ち、種の爆弾は大きく弧を描いて落ちる。仮。 */
    public static double gravity(int kind) {
        return kind == SEED ? 0.05 : kind == SAP ? 0.01 : 0.0;
    }

    /** 空気の抵抗(1 tick に速さに掛ける)。種の爆弾だけ(落ちる速さが頭打ちになる)。仮。 */
    public static double drag(int kind) {
        return kind == SEED ? 0.985 : 1.0;
    }

    /** 弾を足す({@code born} = 撃った tick。その tick の位置から)。 */
    public Shot add(int id, int kind, double x, double y, double z, double vx, double vy, double vz, long born, int life) {
        Shot s = new Shot();
        s.id = id;
        s.kind = kind;
        s.x = s.px = x;
        s.y = s.py = y;
        s.z = s.pz = z;
        s.vx = vx;
        s.vy = vy;
        s.vz = vz;
        s.born = s.tick = born;
        s.life = life;
        list.add(s);
        return s;
    }

    /** 1 tick 進める(位置だけ。当たりは呼ぶ側)。 */
    public static void move(Shot s) {
        s.px = s.x;
        s.py = s.y;
        s.pz = s.z;
        double d = drag(s.kind);
        s.vx *= d;
        s.vz *= d;
        s.vy = s.vy * d - gravity(s.kind);
        s.x += s.vx;
        s.y += s.vy;
        s.z += s.vz;
        s.tick++;
    }

    /** {@code now} まで進める(遅れて来た弾・描画の側)。寿命を過ぎたら {@link Shot#dead}。 */
    public static void advanceTo(Shot s, long now) {
        int n = 0;
        while (s.tick < now && n < 2000) {
            move(s);
            n++;
            if (s.tick - s.born >= s.life) {
                s.dead = true;
                return;
            }
        }
    }

    /** id の弾(無ければ null)。 */
    public Shot find(int id) {
        for (Shot s : list) {
            if (s.id == id) {
                return s;
            }
        }
        return null;
    }

    /**
     * 種の爆弾の弧(重さと空気の抵抗あり)の打ち上げの速さ: 上下の速さ {@code vy} は決めて、横の速さを、高さ {@code at[1]} まで落ちたときに
     * 横へ {@code at} までの距離だけ進むように二分法で探す({@link #move} で実際に飛ばして測る = 両側が飛ばす式と同じ)。
     * 届かなければ届くいちばん遠くへ。結果(ブロック / tick)は {@code out} へ。
     */
    public static void aimLob(double[] from, double[] at, double vy, double[] out) {
        double dx = at[0] - from[0];
        double dz = at[2] - from[2];
        double want = Math.sqrt(dx * dx + dz * dz);
        double ex = want < 1.0E-6 ? 0.0 : dx / want;
        double ez = want < 1.0E-6 ? 0.0 : dz / want;
        double lo = 0.0;
        double hi = 8.0;
        for (int it = 0; it < 30; it++) {
            double mid = (lo + hi) * 0.5;
            if (lobReach(from[1], at[1], mid, vy) < want) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double h = (lo + hi) * 0.5;
        out[0] = ex * h;
        out[1] = vy;
        out[2] = ez * h;
    }

    /** 高さ y0 から、横の速さ h・上下の速さ vy で種の爆弾を打ったとき、高さ y1 まで落ちるまでに横へ進む距離。 */
    public static double lobReach(double y0, double y1, double h, double vy) {
        Shot s = new Shot();
        s.kind = SEED;
        s.y = y0;
        s.vx = h;
        s.vy = vy;
        for (int i = 0; i < 4000; i++) {
            move(s);
            if (s.vy < 0.0 && s.y <= y1) {
                return s.x;
            }
        }
        return s.x;
    }

    /** 対空砲火の弾の種類(狙い方): しっかり狙う・まわりにばらける・離れた所へ。 */
    public static final int AIMED = 0;
    public static final int SCATTERED = 1;
    public static final int WILD = 2;

    /**
     * 対空砲火の 1 発の狙いと時限信管(10-19 夜ユーザー「WW2 の戦艦の分厚い対空砲火」)。乱数 {@code rnd}(0〜1)で、
     * {@link SkyBossTuning#AA_AIMED_SHARE} はしっかり狙い(先回り + 小さなばらつき)、{@link SkyBossTuning#AA_SCATTER_SHARE} は相手のまわり
     * ({@link SkyBossTuning#AA_SCATTER_MIN}〜{@link SkyBossTuning#AA_SCATTER_MAX} ずらした所)、残りは離れた所({@link SkyBossTuning#AA_WILD_MIN}〜{@link SkyBossTuning#AA_WILD_MAX})へ。
     * 時限信管は、撃った所から「弾が着くときの相手の位置」(先回りした所)までの距離を基準に、まわり・離れた所の弾は ± {@link SkyBossTuning#AA_FUSE_SPREAD}、
     * しっかり狙った弾は 0〜+{@link SkyBossTuning#AA_FUSE_SPREAD}(手前で炸裂させない = 相手まで届いて近接の信管で炸裂し、外れたら相手の先で炸裂する)の所で炸裂する寿命(tick)。
     * 10-19 夜のテストクライアント: 撃った瞬間の距離を基準にすると、落ちていく相手を真下から狙った弾が相手の 100〜200 先で炸裂した。
     * 速さを {@code out} へ、{寿命, 種類} を返す。
     */
    public static int[] aimFlak(double[] from, double[] at, double[] vel, double speed, java.util.function.DoubleSupplier rnd, double[] out) {
        double u = rnd.getAsDouble();
        int kind = u < SkyBossTuning.AA_AIMED_SHARE ? AIMED : u < SkyBossTuning.AA_AIMED_SHARE + SkyBossTuning.AA_SCATTER_SHARE ? SCATTERED : WILD;
        double[] aim = at.clone();
        if (kind != AIMED) {
            double lo = kind == SCATTERED ? SkyBossTuning.AA_SCATTER_MIN : SkyBossTuning.AA_WILD_MIN;
            double hi = kind == SCATTERED ? SkyBossTuning.AA_SCATTER_MAX : SkyBossTuning.AA_WILD_MAX;
            double rad = lo + (hi - lo) * rnd.getAsDouble();
            // 球の上の一様な向き
            double z = rnd.getAsDouble() * 2.0 - 1.0;
            double ang = rnd.getAsDouble() * Math.PI * 2.0;
            double rr = Math.sqrt(Math.max(0.0, 1.0 - z * z));
            aim[0] += Math.cos(ang) * rr * rad;
            aim[1] += z * rad;
            aim[2] += Math.sin(ang) * rr * rad;
        }
        lead(from, aim, vel, speed, out);
        if (kind == AIMED) {
            double l = Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2]);
            double a = SkyBossTuning.AA_SPREAD;
            out[0] += (rnd.getAsDouble() * 2.0 - 1.0) * a * l;
            out[1] += (rnd.getAsDouble() * 2.0 - 1.0) * a * l;
            out[2] += (rnd.getAsDouble() * 2.0 - 1.0) * a * l;
            double m = Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2]);
            out[0] *= l / m;
            out[1] *= l / m;
            out[2] *= l / m;
        }
        double t = leadTime(from, at, vel, speed);
        double dx = at[0] + vel[0] * t - from[0];
        double dy = at[1] + vel[1] * t - from[1];
        double dz = at[2] + vel[2] * t - from[2];
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double off = kind == AIMED ? rnd.getAsDouble() : rnd.getAsDouble() * 2.0 - 1.0;
        double fuse = Math.max(speed * 4.0, dist + off * SkyBossTuning.AA_FUSE_SPREAD);
        return new int[]{(int) Math.ceil(fuse / speed), kind};
    }

    /**
     * 動く相手を狙う向き(先回り): 撃つ所 {@code from} から速さ {@code speed} で、相手の今の位置 {@code at} と速さ {@code vel} に当たる向きを {@code out} へ
     * (単位ベクトル × 速さ)。追いつけなければ今の位置へ。重さは考えない(胞子の塊・樹液は速さに比べて小さい)。
     */
    public static void lead(double[] from, double[] at, double[] vel, double speed, double[] out) {
        double t = leadTime(from, at, vel, speed);
        double tx = at[0] - from[0] + vel[0] * t;
        double ty = at[1] - from[1] + vel[1] * t;
        double tz = at[2] - from[2] + vel[2] * t;
        double l = Math.max(1.0E-9, Math.sqrt(tx * tx + ty * ty + tz * tz));
        out[0] = tx / l * speed;
        out[1] = ty / l * speed;
        out[2] = tz / l * speed;
    }

    /** {@link #lead} の当たるまでの時間(tick)。追いつけない・遠すぎる(400 tick より先)なら 0 = 今の位置へ。 */
    public static double leadTime(double[] from, double[] at, double[] vel, double speed) {
        double dx = at[0] - from[0];
        double dy = at[1] - from[1];
        double dz = at[2] - from[2];
        double a = vel[0] * vel[0] + vel[1] * vel[1] + vel[2] * vel[2] - speed * speed;
        double b = 2.0 * (dx * vel[0] + dy * vel[1] + dz * vel[2]);
        double c = dx * dx + dy * dy + dz * dz;
        double t = -1.0;
        if (Math.abs(a) < 1.0E-9) {
            t = b != 0.0 ? -c / b : -1.0;
        } else {
            double disc = b * b - 4.0 * a * c;
            if (disc >= 0.0) {
                double r = Math.sqrt(disc);
                double t1 = (-b - r) / (2.0 * a);
                double t2 = (-b + r) / (2.0 * a);
                t = t1 > 0.0 && (t1 < t2 || t2 <= 0.0) ? t1 : t2;
            }
        }
        return t > 0.0 && t < 400.0 ? t : 0.0;
    }
}
