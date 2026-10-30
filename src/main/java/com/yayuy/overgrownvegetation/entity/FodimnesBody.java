package com.yayuy.overgrownvegetation.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

/**
 * フォディムネスの<b>体の道</b>(頭の通った跡)と、そこから節・尾の位置を出す計算。<b>サーバーとクライアントで同じ</b>
 * (ユーザー指定「同期するのは頭だけで、節の位置はサーバーとクライアントで同じ式から計算する」)。
 *
 * <h2>ユーザー指定(2026-10-01 の原文から)</h2>
 * <ul>
 *   <li>頭の位置と回転(roll)を <b>1 tick ごとにリングバッファへ積み</b>、各節は「履歴を節の間隔 × 番号ぶんさかのぼった点」に置く
 *       (バニラの {@code EnderDragon#positions[64]} / {@code getLatencyPos} が手本)。</li>
 *   <li>履歴の点は<b>スプラインでなめらかにつなぎ、弧長で等間隔</b>に置く。</li>
 *   <li>頭の roll を履歴に積み、<b>位置と同じ遅れ</b>で節ごとに読み出す(ひねりが頭から尾へ順々に伝わる)。</li>
 * </ul>
 *
 * <h2>作り(仮決め)</h2>
 * <ul>
 *   <li>積むのは頭の「軸の点」(実体の足元 + 断面の中心の高さ {@code AXIS_Y})。毎 tick 見て、最新の点から {@link #MIN_STEP} 以上
 *       離れていれば積む —— 止まっている間(待ち伏せ)に同じ点が溜まってバッファが埋まり、体が頭へ縮むのを防ぐため。
 *       その代わり容量が「距離」で決まる: {@link #CAPACITY} × {@link #MIN_STEP} = 20 ブロック ≧ 体の長さ 13.6 + のりしろ。
 *       どんなに遅く動いても(止まっても)体の長さを覆える。まだ積んでいない今の頭の点は、道の先頭として毎回足す。</li>
 *   <li>スプラインは<b>向心の Catmull-Rom</b>(α = 0.5)。点の間隔がばらばらでも尖り・自己交差が出にくい。
 *       区間ごとに 0.1 ブロック以下の折れ線へ細かく分け、折れ線の長さで弧長を測る({@link #TESS_STEP})。</li>
 *   <li>節の向き(前 = 頭の方)は、その点の前後 {@link #TANGENT_H} ブロックの点の差(道の接線)。</li>
 *   <li>道が足りない(出たばかり・読み込み直後・テレポートの直後)ときは、頭の後ろへ<b>まっすぐ</b>の点を積んでから始める
 *       ({@link #seedStraight})。それでも足りなければ、道の終わりの向きへまっすぐ伸ばす。</li>
 * </ul>
 * 計算は折れ線を 1 回作って二分探索で引くだけ(毎 tick、1 体あたり数百回の四則演算)。ワールドには触らない —— GameTest から直接呼べる。
 *
 * <p>長さの定数(積む間隔・折れ線の細かさ・接線の幅・保存の長さ)は<b>体格 1 のときの値</b>で、{@link #setUnit} の体格(SCALE)を掛けて使う
 * (2026-10-01、ユーザー「サイズが小さすぎる」で体格を上げたとき。掛けないと体格 1.5 を超えた所で容量 20 ブロックが体の長さに足りず、
 * 後ろの節が道の終わりからまっすぐ伸びて穴を通らなくなる)。点の数・計算の重さは体格によらない。
 */
public final class FodimnesBody {

    /** リングバッファの点の数。 */
    public static final int CAPACITY = 200;
    /** これだけ動いたら次の点を積む(ブロック)。 */
    public static final double MIN_STEP = 0.1D;
    /** 1 tick にこれ以上跳んだらテレポートとみなして道を捨てる(ブロック)。 */
    public static final double RESET_JUMP = 6.0D;
    /** 折れ線に分けるときの細かさ(ブロック)。 */
    public static final double TESS_STEP = 0.1D;
    /** 接線を測るときに前後へずらす量(ブロック)。 */
    public static final double TANGENT_H = 0.2D;
    /** 保存・同期で送る道の長さの上限(ブロック)。体の長さ 13.6 + のりしろ。 */
    public static final double KEEP_LENGTH = 16.0D;

    private final double[] xs = new double[CAPACITY];
    private final double[] ys = new double[CAPACITY];
    private final double[] zs = new double[CAPACITY];
    private final float[] rolls = new float[CAPACITY];
    /** 最新の点の添字(size が 0 なら意味なし)。 */
    private int newest;
    private int size;

    // ---- 計算の作業場(毎回 new しない)
    private final double[] px = new double[CAPACITY + 1];
    private final double[] py = new double[CAPACITY + 1];
    private final double[] pz = new double[CAPACITY + 1];
    private final float[] pr = new float[CAPACITY + 1];
    private final Path path = new Path(4096);
    /** 長さの定数に掛ける倍率(= 体格)。 */
    private double unit = 1.0D;

    public int size() {
        return this.size;
    }

    /** 長さの定数に掛ける倍率(体格)を決める。体を解く・積む・保存する前に毎回呼ぶ。 */
    public void setUnit(double unit) {
        this.unit = Math.max(0.1D, unit);
    }

    public double unit() {
        return this.unit;
    }

    public void clear() {
        this.size = 0;
        this.newest = 0;
    }

    private int index(int k) {
        return Math.floorMod(this.newest - k, CAPACITY);
    }

    /** k 番目に新しい点(0 = 最新)。 */
    public double x(int k) {
        return this.xs[index(k)];
    }

    public double y(int k) {
        return this.ys[index(k)];
    }

    public double z(int k) {
        return this.zs[index(k)];
    }

    public float roll(int k) {
        return this.rolls[index(k)];
    }

    /** 無条件に積む(古い点はあふれて消える)。 */
    public void pushRaw(double x, double y, double z, float roll) {
        this.newest = this.size == 0 ? 0 : (this.newest + 1) % CAPACITY;
        this.xs[this.newest] = x;
        this.ys[this.newest] = y;
        this.zs[this.newest] = z;
        this.rolls[this.newest] = roll;
        if (this.size < CAPACITY) {
            this.size++;
        }
    }

    /**
     * 毎 tick 呼ぶ: 頭の軸の点を見て、最新の点から {@link #MIN_STEP} 以上離れていれば積む。
     *
     * @return 積んだら true
     */
    public boolean offer(double x, double y, double z, float roll) {
        if (this.size == 0) {
            pushRaw(x, y, z, roll);
            return true;
        }
        double dx = x - x(0);
        double dy = y - y(0);
        double dz = z - z(0);
        double step = MIN_STEP * this.unit;
        if (dx * dx + dy * dy + dz * dz < step * step) {
            return false;
        }
        pushRaw(x, y, z, roll);
        return true;
    }

    /** 最新の点を捨てる(頭が道を後ろへ戻ったとき。頭より前に残った点で道が折り返さないように)。 */
    public void popNewest() {
        if (this.size == 0) {
            return;
        }
        this.size--;
        this.newest = Math.floorMod(this.newest - 1, CAPACITY);
    }

    /**
     * 最後に {@link #solve} した道を、頭から弧長 dist だけさかのぼった点(軸の点)。まだ解いていなければ head を返す。
     * 口を引っ込めるとき(道を戻る)の行き先に使う。
     */
    public Vec3 pointAlongPath(Vec3 head, double dist) {
        if (this.path.n == 0) {
            return head;
        }
        double[] out = new double[4];
        this.path.at(dist, out);
        return new Vec3(out[0], out[1], out[2]);
    }

    /** 最新の点からの距離(道が空なら無限大)。テレポートの判定に使う。 */
    public double distanceFromNewest(double x, double y, double z) {
        if (this.size == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double dx = x - x(0);
        double dy = y - y(0);
        double dz = z - z(0);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * 道を捨てて、頭 (hx, hy, hz) から後ろ向きの単位ベクトル (bx, by, bz) へまっすぐ length ぶんの点を積み直す
     * (古い方から、{@code 2 × MIN_STEP} おき。最新は頭の点そのもの)。
     */
    public void seedStraight(double hx, double hy, double hz, double bx, double by, double bz, double length, float roll) {
        clear();
        double step = 2.0D * MIN_STEP * this.unit;
        int n = (int) Math.ceil(length / step);
        for (int k = n; k >= 0; k--) {
            double d = k * step;
            pushRaw(hx + bx * d, hy + by * d, hz + bz * d, roll);
        }
    }

    // ================================================================== 解く

    /**
     * 頭の今の軸の点 (hx, hy, hz)・roll から、弧長 {@code spacing × (i + 1)}(i = 0 … count−1)の点を出す。
     *
     * @param backX    道が足りないときに伸ばす向き(後ろ向きの単位ベクトル)。道が 2 点以上あれば、道の終わりの向きの方を使う
     * @param outPos   長さ 3·count 以上。点の位置(軸の点)
     * @param outFwd   長さ 3·count 以上。前(頭の方)を向いた単位ベクトル
     * @param outRoll  長さ count 以上。その点の roll(履歴を同じ遅れで読んだもの)
     */
    public void solve(double hx, double hy, double hz, float headRoll, double backX, double backY, double backZ,
                      double spacing, int count, double[] outPos, double[] outFwd, float[] outRoll) {
        double need = spacing * count + 2.0D * spacing + TANGENT_H * this.unit;
        int n = 0;
        this.px[0] = hx;
        this.py[0] = hy;
        this.pz[0] = hz;
        this.pr[0] = headRoll;
        n = 1;
        double chord = 0.0D;
        for (int k = 0; k < this.size && n < this.px.length; k++) {
            double x = x(k);
            double y = y(k);
            double z = z(k);
            double dx = x - this.px[n - 1];
            double dy = y - this.py[n - 1];
            double dz = z - this.pz[n - 1];
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < 1.0E-3D) {
                // 頭がまだ最新の点から動いていない(同じ点を二重に入れると knot の間隔が 0 になる)
                continue;
            }
            this.px[n] = x;
            this.py[n] = y;
            this.pz[n] = z;
            this.pr[n] = roll(k);
            n++;
            chord += d;
            if (chord > need + 1.0D) {
                break;
            }
        }
        solvePoints(this.px, this.py, this.pz, this.pr, n, backX, backY, backZ, spacing, count, this.path, outPos, outFwd, outRoll,
                this.unit);
    }

    /** 体格 1 の {@link #solvePoints(double[], double[], double[], float[], int, double, double, double, double, int, Path, double[], double[], float[], double)}。 */
    public static void solvePoints(double[] px, double[] py, double[] pz, float[] pr, int n,
                                   double backX, double backY, double backZ, double spacing, int count,
                                   Path path, double[] outPos, double[] outFwd, float[] outRoll) {
        solvePoints(px, py, pz, pr, n, backX, backY, backZ, spacing, count, path, outPos, outFwd, outRoll, 1.0D);
    }

    /**
     * 点の列 P[0](頭)… P[n−1](古い)を向心の Catmull-Rom でつなぎ、弧長 {@code spacing × (i + 1)} の点を出す(静的な本体。GameTest から直接呼ぶ)。
     *
     * @param unit 長さの定数(折れ線の細かさ・接線の幅)に掛ける倍率 = 体格
     */
    public static void solvePoints(double[] px, double[] py, double[] pz, float[] pr, int n,
                                   double backX, double backY, double backZ, double spacing, int count,
                                   Path path, double[] outPos, double[] outFwd, float[] outRoll, double unit) {
        tessellate(px, py, pz, pr, n, path, TESS_STEP * unit);
        double bx = backX;
        double by = backY;
        double bz = backZ;
        if (path.n >= 2) {
            // 道の終わりの向き(最後の折れ線の辺)へまっすぐ伸ばす
            int a = path.n - 2;
            int b = path.n - 1;
            double dx = path.x[b] - path.x[a];
            double dy = path.y[b] - path.y[a];
            double dz = path.z[b] - path.z[a];
            double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (l > 1.0E-9D) {
                bx = dx / l;
                by = dy / l;
                bz = dz / l;
            }
        }
        path.endX = bx;
        path.endY = by;
        path.endZ = bz;
        double h = Math.min(TANGENT_H * unit, spacing * 0.5D);
        double[] a = new double[4];
        double[] b = new double[4];
        for (int i = 0; i < count; i++) {
            double s = spacing * (i + 1);
            path.at(s, a);
            outPos[3 * i] = a[0];
            outPos[3 * i + 1] = a[1];
            outPos[3 * i + 2] = a[2];
            outRoll[i] = (float) a[3];
            path.at(s - h, a);
            path.at(s + h, b);
            double fx = a[0] - b[0];
            double fy = a[1] - b[1];
            double fz = a[2] - b[2];
            double l = Math.sqrt(fx * fx + fy * fy + fz * fz);
            if (l < 1.0E-9D) {
                fx = -bx;
                fy = -by;
                fz = -bz;
                l = 1.0D;
            }
            outFwd[3 * i] = fx / l;
            outFwd[3 * i + 1] = fy / l;
            outFwd[3 * i + 2] = fz / l;
        }
    }

    /** 点の列を向心の Catmull-Rom の折れ線にする(区間ごとに tessStep({@link #TESS_STEP} × 体格)以下の辺へ分ける)。 */
    static void tessellate(double[] px, double[] py, double[] pz, float[] pr, int n, Path path, double tessStep) {
        path.n = 0;
        if (n <= 0) {
            return;
        }
        path.add(px[0], py[0], pz[0], pr[0]);
        double[] c = new double[3];
        for (int k = 0; k + 1 < n; k++) {
            // 前後の点(端は鏡に写した点)
            double x0;
            double y0;
            double z0;
            if (k == 0) {
                x0 = 2.0D * px[0] - px[1];
                y0 = 2.0D * py[0] - py[1];
                z0 = 2.0D * pz[0] - pz[1];
            } else {
                x0 = px[k - 1];
                y0 = py[k - 1];
                z0 = pz[k - 1];
            }
            double x3;
            double y3;
            double z3;
            if (k + 2 < n) {
                x3 = px[k + 2];
                y3 = py[k + 2];
                z3 = pz[k + 2];
            } else {
                x3 = 2.0D * px[k + 1] - px[k];
                y3 = 2.0D * py[k + 1] - py[k];
                z3 = 2.0D * pz[k + 1] - pz[k];
            }
            double chord = dist(px[k], py[k], pz[k], px[k + 1], py[k + 1], pz[k + 1]);
            int m = Math.max(1, Math.min(32, (int) Math.ceil(chord / tessStep)));
            for (int j = 1; j <= m; j++) {
                double u = (double) j / m;
                if (j == m) {
                    c[0] = px[k + 1];
                    c[1] = py[k + 1];
                    c[2] = pz[k + 1];
                } else {
                    catmullRom(x0, y0, z0, px[k], py[k], pz[k], px[k + 1], py[k + 1], pz[k + 1], x3, y3, z3, u, c);
                }
                float roll = (float) (pr[k] + (pr[k + 1] - pr[k]) * u);
                if (!path.add(c[0], c[1], c[2], roll)) {
                    return;
                }
            }
        }
    }

    /**
     * 向心の Catmull-Rom(Barry–Goldman の組み立て、α = 0.5)。P1 → P2 の区間の、割合 u(0〜1)の点。
     * 点の間隔が 0 に近い区間は間隔を小さな値に置き換える(割り算を避ける)。
     */
    public static void catmullRom(double x0, double y0, double z0, double x1, double y1, double z1,
                                  double x2, double y2, double z2, double x3, double y3, double z3, double u, double[] out) {
        double t0 = 0.0D;
        double t1 = t0 + knot(x0, y0, z0, x1, y1, z1);
        double t2 = t1 + knot(x1, y1, z1, x2, y2, z2);
        double t3 = t2 + knot(x2, y2, z2, x3, y3, z3);
        double t = t1 + (t2 - t1) * u;
        for (int a = 0; a < 3; a++) {
            double p0 = a == 0 ? x0 : a == 1 ? y0 : z0;
            double p1 = a == 0 ? x1 : a == 1 ? y1 : z1;
            double p2 = a == 0 ? x2 : a == 1 ? y2 : z2;
            double p3 = a == 0 ? x3 : a == 1 ? y3 : z3;
            double a1 = ((t1 - t) * p0 + (t - t0) * p1) / (t1 - t0);
            double a2 = ((t2 - t) * p1 + (t - t1) * p2) / (t2 - t1);
            double a3 = ((t3 - t) * p2 + (t - t2) * p3) / (t3 - t2);
            double b1 = ((t2 - t) * a1 + (t - t0) * a2) / (t2 - t0);
            double b2 = ((t3 - t) * a2 + (t - t1) * a3) / (t3 - t1);
            out[a] = ((t2 - t) * b1 + (t - t1) * b2) / (t2 - t1);
        }
    }

    private static double knot(double ax, double ay, double az, double bx, double by, double bz) {
        return Math.max(1.0E-3D, Math.sqrt(dist(ax, ay, az, bx, by, bz)));
    }

    static double dist(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ================================================================== 保存・同期

    /** 最新から {@link #KEEP_LENGTH} × 体格ぶんの点の数(保存・同期で送る数)。 */
    private int keptCount() {
        int count = 0;
        double len = 0.0D;
        double keep = KEEP_LENGTH * this.unit;
        for (int k = 0; k < this.size; k++) {
            if (k > 0) {
                len += dist(x(k - 1), y(k - 1), z(k - 1), x(k), y(k), z(k));
            }
            count++;
            if (len > keep) {
                break;
            }
        }
        return count;
    }

    /** 道を頭 (hx, hy, hz) からの相対の float で保存する(古い方から)。 */
    public void save(CompoundTag tag, String key, double hx, double hy, double hz) {
        int count = keptCount();
        int[] data = new int[count * 4];
        for (int j = 0; j < count; j++) {
            int k = count - 1 - j;
            data[4 * j] = Float.floatToIntBits((float) (x(k) - hx));
            data[4 * j + 1] = Float.floatToIntBits((float) (y(k) - hy));
            data[4 * j + 2] = Float.floatToIntBits((float) (z(k) - hz));
            data[4 * j + 3] = Float.floatToIntBits(roll(k));
        }
        tag.putIntArray(key, data);
    }

    /** {@link #save} の逆。道が無ければ false(呼んだ側がまっすぐに置く)。 */
    public boolean load(CompoundTag tag, String key, double hx, double hy, double hz) {
        clear();
        if (!tag.contains(key)) {
            return false;
        }
        int[] data = tag.getIntArray(key);
        for (int j = 0; j + 3 < data.length; j += 4) {
            float x = Float.intBitsToFloat(data[j]);
            float y = Float.intBitsToFloat(data[j + 1]);
            float z = Float.intBitsToFloat(data[j + 2]);
            float r = Float.intBitsToFloat(data[j + 3]);
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || !Float.isFinite(r)) {
                clear();
                return false;
            }
            pushRaw(hx + x, hy + y, hz + z, r);
        }
        return this.size > 0;
    }

    /** 出現のパケットへ書く(クライアントがサーバーと同じ道から始めるため)。 */
    public void write(FriendlyByteBuf buf, double hx, double hy, double hz) {
        int count = Math.min(keptCount(), CAPACITY);
        buf.writeVarInt(count);
        for (int j = 0; j < count; j++) {
            int k = count - 1 - j;
            buf.writeFloat((float) (x(k) - hx));
            buf.writeFloat((float) (y(k) - hy));
            buf.writeFloat((float) (z(k) - hz));
            buf.writeFloat(roll(k));
        }
    }

    /** {@link #write} の逆。 */
    public boolean read(FriendlyByteBuf buf, double hx, double hy, double hz) {
        clear();
        int count = buf.readVarInt();
        if (count < 0 || count > CAPACITY) {
            return false;
        }
        for (int j = 0; j < count; j++) {
            float x = buf.readFloat();
            float y = buf.readFloat();
            float z = buf.readFloat();
            float r = buf.readFloat();
            if (Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z) && Float.isFinite(r)) {
                pushRaw(hx + x, hy + y, hz + z, r);
            }
        }
        return this.size > 0;
    }

    // ================================================================== 折れ線

    /** 折れ線(頭から後ろへ)と、その上の弧長での引き。 */
    public static final class Path {
        final double[] x;
        final double[] y;
        final double[] z;
        final double[] roll;
        /** 頭からの折れ線の長さ。 */
        final double[] len;
        int n;
        double endX;
        double endY;
        double endZ;

        public Path(int capacity) {
            this.x = new double[capacity];
            this.y = new double[capacity];
            this.z = new double[capacity];
            this.roll = new double[capacity];
            this.len = new double[capacity];
        }

        boolean add(double px, double py, double pz, float r) {
            if (this.n >= this.x.length) {
                return false;
            }
            this.x[this.n] = px;
            this.y[this.n] = py;
            this.z[this.n] = pz;
            this.roll[this.n] = r;
            this.len[this.n] = this.n == 0 ? 0.0D : this.len[this.n - 1] + dist(this.x[this.n - 1], this.y[this.n - 1], this.z[this.n - 1], px, py, pz);
            this.n++;
            return true;
        }

        /** 折れ線の全長。 */
        public double length() {
            return this.n == 0 ? 0.0D : this.len[this.n - 1];
        }

        /** 弧長 s の点(out = x, y, z, roll)。全長より先は終わりの向きへまっすぐ伸ばす。 */
        public void at(double s, double[] out) {
            if (this.n == 0) {
                out[0] = 0.0D;
                out[1] = 0.0D;
                out[2] = 0.0D;
                out[3] = 0.0D;
                return;
            }
            double total = this.len[this.n - 1];
            if (s >= total || this.n == 1) {
                int e = this.n - 1;
                double over = Math.max(0.0D, s - total);
                out[0] = this.x[e] + this.endX * over;
                out[1] = this.y[e] + this.endY * over;
                out[2] = this.z[e] + this.endZ * over;
                out[3] = this.roll[e];
                return;
            }
            if (s <= 0.0D) {
                out[0] = this.x[0];
                out[1] = this.y[0];
                out[2] = this.z[0];
                out[3] = this.roll[0];
                return;
            }
            // len[i] <= s < len[i+1] を二分探索
            int lo = 0;
            int hi = this.n - 1;
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (this.len[mid] <= s) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            double seg = this.len[hi] - this.len[lo];
            double f = seg < 1.0E-12D ? 0.0D : (s - this.len[lo]) / seg;
            out[0] = this.x[lo] + (this.x[hi] - this.x[lo]) * f;
            out[1] = this.y[lo] + (this.y[hi] - this.y[lo]) * f;
            out[2] = this.z[lo] + (this.z[hi] - this.z[lo]) * f;
            out[3] = this.roll[lo] + (this.roll[hi] - this.roll[lo]) * f;
        }
    }
}
