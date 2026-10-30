package com.yayuy.overgrownvegetation.swarm;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 群れの<b>足跡</b>(群れ v2、2026-09-25)。先導役が歩いた区間の経路の点を、<b>通り道の中心</b>として古い順に並べた輪。
 *
 * <p>点は先導役の位置そのものではなく、区間の経路のノード + 包絡の幅のずれ({@code InvaderNavigation.Envelope#corridorOffset})。
 * バニラは太い体をノードの角から占有させ、細い先導役はノード + 0.5 を歩くので、先導役の位置を記録すると
 * 包絡で確かめた通り道の端をなぞることになる(レビュー指摘「幅の広い後続が確かめていない列をこする」)。
 *
 * <p>各点は通り方の<b>種類</b>({@link Kind})を持つ。後続は種類を見て、壁に張り付く・水から上がらない・跳ぶを決める。
 *
 * <h2>番号(seq)</h2>
 * 点は追加された順に増え続ける番号で指す。輪が一周して古い点が消えても番号はずれないので、後続の
 * 「次に向かう点」(カーソル)は番号で持てばよい。{@link #tailSeq()} より小さい番号は消えた点。
 *
 * <p>サーバースレッドだけが触る。
 */
public final class Trail {

    /** 点の通り方。 */
    public enum Kind {
        /** 歩く。 */
        WALK,
        /** 壁を登る・降りる(区間の経路の縦の辺)。 */
        CLIMB,
        /** 泳ぐ(区間の経路の水のノード)。 */
        SWIM,
        /** 先導役がここへ跳んだ(川の跳び越え)。この点の手前は地面が続いていない。 */
        LEAP
    }

    /** これより近い点は足さない(ブロック)。同じノードを 2 度記録しない・止まっている先導役で輪を埋めない。 */
    private static final double MIN_SPACING = 0.35D;

    private final int capacity;
    private final double[] xs;
    private final double[] ys;
    private final double[] zs;
    private final Kind[] kinds;
    /** 次に足す点の番号(= いちばん新しい点の番号 + 1)。 */
    private long head;
    /** いちばん古い点の番号。 */
    private long tail;

    /** 点が変わるたびに増える数(道のりの控えが古いかの判定)。 */
    private long version;
    /** 新しい点から数えた道のりの控え({@link #lengthCacheVersion} のときの値)。{@code cumulative[i]} = 新しいほうから i 番目までの道のり。 */
    private double[] cumulative = new double[0];
    private long lengthCacheVersion = -1L;

    public Trail(int capacity) {
        this.capacity = Math.max(2, capacity);
        this.xs = new double[this.capacity];
        this.ys = new double[this.capacity];
        this.zs = new double[this.capacity];
        this.kinds = new Kind[this.capacity];
    }

    // --- 読む ---

    /** 次に足す点の番号(いちばん新しい点は {@code headSeq() - 1})。 */
    public long headSeq() {
        return this.head;
    }

    /** いちばん古い点の番号。 */
    public long tailSeq() {
        return this.tail;
    }

    /** いちばん新しい点の番号。空なら -1。 */
    public long newestSeq() {
        return isEmpty() ? -1L : this.head - 1L;
    }

    public boolean isEmpty() {
        return this.head == this.tail;
    }

    public int size() {
        return (int) (this.head - this.tail);
    }

    /** その番号の点がまだ残っているか。 */
    public boolean has(long seq) {
        return seq >= this.tail && seq < this.head;
    }

    private int index(long seq) {
        return (int) Math.floorMod(seq, (long) this.capacity);
    }

    /** 点の位置(通り道の中心、足元の高さ)。{@link #has} でないと中身は保証しない。 */
    public Vec3 point(long seq) {
        int i = index(seq);
        return new Vec3(this.xs[i], this.ys[i], this.zs[i]);
    }

    public double x(long seq) {
        return this.xs[index(seq)];
    }

    public double y(long seq) {
        return this.ys[index(seq)];
    }

    public double z(long seq) {
        return this.zs[index(seq)];
    }

    public Kind kind(long seq) {
        Kind kind = this.kinds[index(seq)];
        return kind != null ? kind : Kind.WALK;
    }

    /** いちばん新しい点。空なら null。 */
    @Nullable
    public Vec3 newest() {
        return isEmpty() ? null : point(this.head - 1L);
    }

    // --- 書く ---

    /**
     * 点を足す。いちばん新しい点から {@value #MIN_SPACING} ブロック以内なら足さず、種類だけ強いほう
     * (WALK より CLIMB / SWIM / LEAP)へ上書きする。
     */
    public void append(double x, double y, double z, Kind kind) {
        if (!isEmpty()) {
            int last = index(this.head - 1L);
            double dx = x - this.xs[last];
            double dy = y - this.ys[last];
            double dz = z - this.zs[last];
            if (dx * dx + dy * dy + dz * dz < MIN_SPACING * MIN_SPACING) {
                if (kind != Kind.WALK) {
                    this.kinds[last] = kind;
                }
                return;
            }
        }
        int i = index(this.head);
        this.xs[i] = x;
        this.ys[i] = y;
        this.zs[i] = z;
        this.kinds[i] = kind;
        this.head++;
        this.version++;
        if (this.head - this.tail > this.capacity) {
            this.tail = this.head - this.capacity;
        }
    }

    public void append(Vec3 pos, Kind kind) {
        append(pos.x, pos.y, pos.z, kind);
    }

    /**
     * {@code seq} より新しい点を捨てる(先導役が替わったとき、新しい先導役より前の足跡を消す)。
     * 残す点は<b>新しい番号で</b>足し直す —— 番号を巻き戻すと、後続のカーソル(古い番号)が別の点を指してしまう。
     * 古い番号のカーソルは {@link #has} が false になるので、後続はいまの位置にいちばん近い点から追い直す。
     */
    public void truncateAfter(long seq) {
        if (seq >= this.head - 1L) {
            return;
        }
        long keepTo = Math.min(seq, this.head - 1L);
        int n = seq < this.tail ? 0 : (int) (keepTo - this.tail + 1L);
        double[] kx = new double[n];
        double[] ky = new double[n];
        double[] kz = new double[n];
        Kind[] kk = new Kind[n];
        for (int k = 0; k < n; k++) {
            long s = this.tail + k;
            kx[k] = x(s);
            ky[k] = y(s);
            kz[k] = z(s);
            kk[k] = kind(s);
        }
        clear();
        for (int k = 0; k < n; k++) {
            append(kx[k], ky[k], kz[k], kk[k]);
        }
    }

    /** 全部捨てる(番号は続きから)。 */
    public void clear() {
        this.tail = this.head;
        this.version++;
    }

    // --- 探す ---

    /** {@code pos} にいちばん近い点の番号。空なら -1。 */
    public long nearestSeq(Vec3 pos) {
        return nearestSeq(pos, this.tail, this.head);
    }

    /** {@code [from, to)} の中で {@code pos} にいちばん近い点の番号。無ければ -1。同じ距離なら新しいほう。 */
    public long nearestSeq(Vec3 pos, long from, long to) {
        long lo = Math.max(from, this.tail);
        long hi = Math.min(to, this.head);
        long best = -1L;
        double bestSq = Double.MAX_VALUE;
        for (long s = lo; s < hi; s++) {
            int i = index(s);
            double dx = pos.x - this.xs[i];
            double dy = pos.y - this.ys[i];
            double dz = pos.z - this.zs[i];
            double sq = dx * dx + dy * dy + dz * dz;
            if (sq <= bestSq) {
                bestSq = sq;
                best = s;
            }
        }
        return best;
    }

    /** いちばん新しい点から {@code seq} までの道のり(点を結んだ折れ線の長さ)。 */
    public double lengthFromNewest(long seq) {
        if (!has(seq)) {
            return seq >= this.head ? 0.0D : totalLength();
        }
        rebuildLengths();
        return this.cumulative[(int) (this.head - 1L - seq)];
    }

    /** 足跡全体の道のり。 */
    public double totalLength() {
        if (isEmpty()) {
            return 0.0D;
        }
        rebuildLengths();
        return this.cumulative[this.cumulative.length - 1];
    }

    /**
     * いちばん新しい点から道のりで {@code distance} 戻った位置({@link Anchor})。
     * 足跡が短ければ、いちばん古い点からさらに後ろへ延ばす(延ばす向きは古い 2 点の向き。1 点しか無ければ {@code fallbackBack})。
     *
     * @param fallbackBack 点が 1 つしか無いとき「後ろ」とみなす水平の向き(先導役の背中側など)。長さは問わない
     */
    public Anchor behindNewest(double distance, Vec3 fallbackBack) {
        if (isEmpty()) {
            return new Anchor(-1L, Vec3.ZERO, fallbackBack, true);
        }
        rebuildLengths();
        int n = this.cumulative.length;
        double d = Math.max(0.0D, distance);
        // cumulative は新しい点(0)から古い点(n-1)へ増える。d を超える最初の区間を二分探索で探す。
        if (d <= this.cumulative[n - 1]) {
            int lo = 0;
            int hi = n - 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (this.cumulative[mid] < d) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            long seq = this.head - 1L - lo;
            Vec3 at = point(seq);
            Vec3 tangent = tangentAt(seq, fallbackBack);
            if (lo > 0) {
                // 1 つ新しい点との間を、道のりで割り振った位置にする。
                long newer = seq + 1L;
                double segment = this.cumulative[lo] - this.cumulative[lo - 1];
                if (segment > 1.0E-6D) {
                    double t = (this.cumulative[lo] - d) / segment;
                    at = at.lerp(point(newer), t);
                }
            }
            return new Anchor(seq, at, tangent, false);
        }
        // 足跡より後ろ。いちばん古い点から後ろへ延ばす。
        long oldest = this.tail;
        Vec3 back;
        if (size() >= 2) {
            Vec3 o = point(oldest);
            Vec3 next = point(oldest + 1L);
            back = new Vec3(o.x - next.x, 0.0D, o.z - next.z);
        } else {
            back = new Vec3(fallbackBack.x, 0.0D, fallbackBack.z);
        }
        double len = back.length();
        back = len > 1.0E-6D ? back.scale(1.0D / len) : new Vec3(0.0D, 0.0D, 1.0D);
        double extra = d - this.cumulative[n - 1];
        Vec3 at = point(oldest).add(back.scale(extra));
        return new Anchor(oldest, at, back.scale(-1.0D), true);
    }

    /** {@code seq} での進む向き(古い点 → 新しい点、水平の単位ベクトル)。 */
    private Vec3 tangentAt(long seq, Vec3 fallbackBack) {
        long a = seq;
        long b = seq + 1L;
        if (!has(b)) {
            a = seq - 1L;
            b = seq;
        }
        Vec3 dir;
        if (has(a) && has(b)) {
            dir = new Vec3(x(b) - x(a), 0.0D, z(b) - z(a));
        } else {
            dir = new Vec3(-fallbackBack.x, 0.0D, -fallbackBack.z);
        }
        double len = dir.length();
        return len > 1.0E-6D ? dir.scale(1.0D / len) : new Vec3(0.0D, 0.0D, 1.0D);
    }

    /** 道のりの控えを作り直す(点が変わったときだけ)。 */
    private void rebuildLengths() {
        if (this.lengthCacheVersion == this.version) {
            return;
        }
        int n = size();
        if (this.cumulative.length != n) {
            this.cumulative = new double[n];
        }
        double acc = 0.0D;
        for (int k = 0; k < n; k++) {
            long seq = this.head - 1L - k;
            if (k > 0) {
                long newer = seq + 1L;
                double dx = x(newer) - x(seq);
                double dy = y(newer) - y(seq);
                double dz = z(newer) - z(seq);
                acc += Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
            this.cumulative[k] = acc;
        }
        this.lengthCacheVersion = this.version;
    }

    /**
     * 足跡の上の 1 点。
     *
     * @param seq      その位置のすぐ古い側の点の番号(足跡より後ろへ延ばしたときはいちばん古い点)
     * @param pos      位置
     * @param tangent  その位置での進む向き(水平の単位ベクトル)
     * @param extended 足跡より後ろへ延ばした位置か(確かめていない場所)
     */
    public record Anchor(long seq, Vec3 pos, Vec3 tangent, boolean extended) {
    }
}
