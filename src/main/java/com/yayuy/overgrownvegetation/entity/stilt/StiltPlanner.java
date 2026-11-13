package com.yayuy.overgrownvegetation.entity.stilt;

import com.yayuy.overgrownvegetation.entity.forest.CoarseDetour;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 竹馬型の胴の経路(2026-11-10。種によらない)。
 *
 * <p>バニラの経路探索は「足元の 1 マスに体が立つ」作りで、地面から 2.5〜6.5 ブロック上を進む胴と、そのまわり 3 ブロックに散る足場を
 * 扱えない。ここでは<b>ブロックの角を節</b>にした 2D の A* で胴の通り道を探す(胴の中心 = 角、胴の箱 1.5 × 1.5 は角のまわりの 2 × 2 の列に乗る)。
 * 節ごとに {@link #evaluate} で<b>胴の高さ</b>を 1 つ決める:
 * <ol>
 *   <li>胴の下(2 × 2 の列)に地面があること。幹・壁の列は通れない。</li>
 *   <li>脚の置き場: 胴のまわり 4 つの区画(角から 1〜3 列)のうち 3 つ以上に足場があること。</li>
 *   <li>高さの帯: 下は「胴の下の一番高い地面 + 0.5」「水面」「まわりの地面 + 2.5(かがむ限り)」、上は「まわりの地面 + 6.5」と
 *       「一番低い区画の足場から脚が届く高さ」。段差のまたぎ(5 段まで)はこの 2 つで決まり、6 段では帯が空になる。</li>
 *   <li>その帯の中で、胴の箱が葉・枝・天井に当たらない一番高い所(ふつうの高さ {@link StiltParams#preferHeight} を上限に)。</li>
 *   <li>かがんでいる({@link StiltParams#crouchBelow} より低い)なら、膝の張り出す外側の列に幹・壁が無いこと。</li>
 * </ol>
 * 隣の節へは、両方が通れて胴の高さの差が {@link #MAX_DB} 以内のとき進める(斜めは両脇も通れるとき)。
 *
 * <p>重さ: 節 1 つに列の調べ約 50 回(多くは {@link StiltColumns} の使い回し)。サーバー全体の予算はファルキムネスの回り道と同じ
 * {@link CoarseDetour#budgetLeft}(2 ms/tick)を分け合う。
 */
public final class StiltPlanner {

    /** 調べる節の数の上限・探す半径(ブロック)【仮】。 */
    static final int MAX_EXPAND = 3000, RADIUS = 40;
    /** 1 回の探索の時間の上限(ns)【仮 1.5 ms】。打ち切っても節の答えは使い回すので、次の探し直しで先へ進む。サーバー全体の予算 2 ms/tick(CoarseDetour)も分け合う。 */
    public static final long TIME_CAP = 1_500_000L;
    /** 隣の節との胴の高さの差の上限(ブロック)【仮】。 */
    static final double MAX_DB = 6.0D;   // 段のまたぎは歩く側が「足から届く高さ」に抑えて越える(11-10。2.6 だと 5 段の際で節の高さが 4 跳んで上れなかった)
    /** 計測用。 */
    public static volatile long nanos, calls, expanded, maxNanos;

    private StiltPlanner() {
    }

    /** 節(角 x, z)の答え。b = 胴の箱の底の高さ。open = 開けている度合い(0〜1、足場の多さ)。 */
    public record Node(int x, int z, double b, double ground, double open) {
        public double cx() {
            return x;
        }

        public double cz() {
            return z;
        }
    }

    /** 経路(始めの節を除く)と、目標まで届いたか・時間の上限で打ち切ったか。 */
    public record Plan(List<Node> nodes, boolean reaches, int expanded, long nanos, boolean capped) {
    }

    // ------------------------------------------------------------------ 節の答えの使い回し(探し直しのたびに同じ節を調べ直さない)

    /** 節の答えを使い回す tick【仮】。 */
    static final long NODE_TICKS = 100L;
    private static final Object BLOCKED = new Object();

    private record CachedNode(Object v, long at) {
    }

    private static final java.util.Map<ServerLevel, it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<CachedNode>> NODES = new java.util.WeakHashMap<>();
    private static long lastSweep;

    private static long nodeKey(int x, int z, double ref) {
        int bucket = (int) Math.floor(ref) >> 2;
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (bucket & 0xFFF);
    }

    /** 試験用: 節の答えを捨てる。 */
    public static synchronized void forget(ServerLevel level) {
        NODES.remove(level);
    }

    /** 使い回しつきの {@link #evaluate}。 */
    @Nullable
    public static Node evaluateCached(ServerLevel level, StiltParams p, int x, int z, double ref) {
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<CachedNode> m;
        synchronized (StiltPlanner.class) {
            m = NODES.computeIfAbsent(level, l -> new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>());
        }
        long now = level.getGameTime();
        long k = nodeKey(x, z, ref);
        CachedNode c = m.get(k);
        if (c != null && now - c.at() <= NODE_TICKS) {
            return c.v() == BLOCKED ? null : (Node) c.v();
        }
        if (now - lastSweep > 200L) {
            lastSweep = now;
            m.values().removeIf(v -> now - v.at() > NODE_TICKS);
        }
        Node nd = evaluate(level, p, x, z, ref);
        m.put(k, new CachedNode(nd == null ? BLOCKED : nd, now));
        return nd;
    }

    /**
     * 節 (x, z) を、だいたいの地面の高さ {@code ref} のまわりで調べる。通れなければ null。
     */
    @Nullable
    public static Node evaluate(ServerLevel level, StiltParams p, int x, int z, double ref) {
        StiltColumns cols = StiltColumns.of(level);
        // 1. 胴の下
        double gFp = -Double.MAX_VALUE, waterTop = -Double.MAX_VALUE;
        StiltColumns.Column[] fp = new StiltColumns.Column[4];
        for (int i = 0; i < 4; i++) {
            StiltColumns.Column c = cols.at(x - 1 + (i & 1), z - 1 + (i >> 1), ref);
            if (!c.hasGround() || c.lava()) return null;
            fp[i] = c;
            gFp = Math.max(gFp, c.surface());
            waterTop = Math.max(waterTop, c.ground() + c.water());
        }
        // 2. 脚の区画: 角から 1〜3 列(区画ごとに、胴の下の地面 + 1 より高くない一番高い足場)
        double sum = 0.0D, low = Double.MAX_VALUE, high = -Double.MAX_VALUE;
        int have = 0, footholds = 0, cells = 0;
        for (int q = 0; q < 4; q++) {
            int sx = (q & 1) == 0 ? -1 : 1, sz = (q >> 1) == 0 ? -1 : 1;
            double best = -Double.MAX_VALUE;
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    int cx = sx < 0 ? x - 1 - i : x + i, cz = sz < 0 ? z - 1 - j : z + j;
                    if (i == 0 && j == 0) continue;   // 胴の真下の列
                    StiltColumns.Column c = cols.at(cx, cz, ref);
                    cells++;
                    if (!c.foothold(p.maxWaterDepth) || c.surface() > gFp + 1.0D) continue;
                    footholds++;
                    best = Math.max(best, c.surface());
                }
            }
            if (best > -Double.MAX_VALUE) {
                have++;
                sum += best;
                low = Math.min(low, best);
                high = Math.max(high, best);
            }
        }
        if (have < 3) return null;
        double gMed = sum / have;
        // 3. 高さの帯(箱の底)
        double half = p.bodyHalf;
        double vertical = Math.sqrt(Math.max(0.0D, p.legReach * p.legReach - 1.4D * 1.4D)) + p.ankleUp;   // 足先が付け根から横へ約 1.4 のとき
        double bMin = Math.max(Math.max(gFp + 0.5D, waterTop - 0.3D), gMed + p.minHeight - half);
        // 付け根が一番高い足場より下がらない(岸に残る後ろ足、11-10)
        bMin = Math.max(bMin, high + p.minDrop - 0.4D - half - p.hipUp + 0.05D);
        double bMax = Math.min(gMed + p.maxHeight - half, low + vertical - half - p.hipUp);
        bMax = Math.min(bMax, gFp + p.maxHeight - half + 1.0D);
        if (bMax < bMin) return null;
        double prefer = Mth.clamp(gMed + p.preferHeight - half, bMin, bMax);
        // 4. 胴の箱が当たらない一番高い所(ふつうの高さから下へ、無ければ上へ)
        double b = Double.NaN;
        for (double t = prefer; t >= bMin - 1.0E-6D; t -= 0.25D) {
            if (clear(fp, t, half)) {
                b = t;
                break;
            }
        }
        if (Double.isNaN(b)) {
            for (double t = prefer + 0.25D; t <= bMax + 1.0E-6D; t += 0.25D) {
                if (clear(fp, t, half)) {
                    b = t;
                    break;
                }
            }
        }
        if (Double.isNaN(b)) return null;
        // 5. かがむなら膝の外側(胴の下 2 × 2 を囲む 12 列)に幹・壁が無いこと
        if (b + half - gMed < p.crouchBelow) {
            for (int dx = -2; dx <= 1; dx++) {
                for (int dz = -2; dz <= 1; dz++) {
                    if (dx >= -1 && dx <= 0 && dz >= -1 && dz <= 0) continue;
                    StiltColumns.Column c = cols.at(x + dx, z + dz, ref);
                    if (!c.hasGround()) return null;   // 幹・壁の列
                    if (c.firmBetween(b + 0.5D, b + 2.75D)) return null;
                }
            }
        }
        double open = cells == 0 ? 0.0D : (double) footholds / cells;
        return new Node(x, z, b, gMed, open);
    }

    /**
     * 段 2(11-14): 角 (x, z) で胴の中心を地面から {@code height} まで下ろせるか(覗く立ち位置を選ぶとき)。通れる節で、
     * 胴の箱がその高さで何にも当たらず、膝が張り出す外側の 12 列に幹・壁が無いこと。{@link #evaluate} は一番高い胴を返すだけなので別に見る。
     */
    public static boolean canCrouch(ServerLevel level, StiltParams p, int x, int z, double ref, double height) {
        Node n = evaluateCached(level, p, x, z, ref);
        if (n == null) return false;
        StiltColumns cols = StiltColumns.of(level);
        double b = n.ground() + height - p.bodyHalf;
        StiltColumns.Column[] fp = new StiltColumns.Column[4];
        for (int i = 0; i < 4; i++) {
            fp[i] = cols.at(x - 1 + (i & 1), z - 1 + (i >> 1), ref);
            if (!fp[i].hasGround() || fp[i].surface() > b - 0.3D) return false;
        }
        if (!clear(fp, b, p.bodyHalf)) return false;
        for (int dx = -2; dx <= 1; dx++) {
            for (int dz = -2; dz <= 1; dz++) {
                if (dx >= -1 && dx <= 0 && dz >= -1 && dz <= 0) continue;
                StiltColumns.Column c = cols.at(x + dx, z + dz, ref);
                if (!c.hasGround() || c.firmBetween(b + 0.5D, b + 2.75D)) return false;
            }
        }
        return true;
    }

    private static boolean clear(StiltColumns.Column[] fp, double b, double half) {
        for (StiltColumns.Column c : fp) {
            if (c.blocked(b, b + half * 2.0D)) return false;
        }
        return true;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static final class Open implements Comparable<Open> {
        final Node n;
        final double g, f;
        final Open parent;

        Open(Node n, double g, double f, Open parent) {
            this.n = n;
            this.g = g;
            this.f = f;
            this.parent = parent;
        }

        @Override
        public int compareTo(Open o) {
            return Double.compare(this.f, o.f);
        }
    }

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /**
     * 角 (fx, fz) から (tx, tz) への胴の経路を、{@link #TIME_CAP} の中で 1 回探す(届かなければ一番近づける所まで)。
     * 打ち切ったら {@link Plan#capped()}。歩く側は {@link Search} で続きから探す。
     */
    @Nullable
    public static Plan plan(ServerLevel level, StiltParams p, int fx, int fz, double ref, int tx, int tz) {
        Search s = Search.start(level, p, fx, fz, ref, tx, tz);
        if (s == null) return null;
        Plan pl = s.run(TIME_CAP);
        return pl != null ? pl : s.partial();
    }

    /**
     * 続きから探せる A*(11-11)。前は打ち切るたびに最初からやり直し、届かない目標では 1.5 ms の探索を 2 tick おきに
     * 何十回も繰り返した(10 体で 600 tick に約 250 回)。ここでは開いた節・調べた節を持ち越し、{@link #run} は与えた時間だけ進めて、
     * 終わっていなければ null を返す(次の tick に続ける)。全体の仕事は 1 回の探索ぶんで済む。
     * 始めの角から {@link #RADIUS}・節 {@link #MAX_EXPAND} まで調べて目標が無ければ、一番近づける所までの経路で終わる。
     */
    public static final class Search {
        private final ServerLevel level;
        private final StiltParams p;
        public final int tx, tz;
        private final Node start;
        // 箱詰めの要らない表(探索 1 回で数千の節を入れるので、ゴミを減らす、11-11)
        private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Node> nodes = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        private final it.unimi.dsi.fastutil.longs.LongOpenHashSet blocked = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        private final it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap best = new it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap();
        private final PriorityQueue<Open> open = new PriorityQueue<>();
        private Open closest;
        private double closestH;
        private int expanded;
        private long spent;

        private Search(ServerLevel level, StiltParams p, Node start, int tx, int tz) {
            this.level = level;
            this.p = p;
            this.start = start;
            this.tx = tx;
            this.tz = tz;
            Open first = new Open(start, 0.0D, h(start.x, start.z, tx, tz), null);
            this.open.add(first);
            this.nodes.put(key(start.x, start.z), start);
            this.best.put(key(start.x, start.z), 0.0D);
            this.best.defaultReturnValue(Double.NaN);
            this.closest = first;
            this.closestH = first.f;
        }

        /** 始める。始めの節が通れないとき(押されて変な所にいる)は、まわり 2 列で一番近い通れる節から。どこも通れなければ null。 */
        @Nullable
        public static Search start(ServerLevel level, StiltParams p, int fx, int fz, double ref, int tx, int tz) {
            long t0 = System.nanoTime();
            Node start = evaluateCached(level, p, fx, fz, ref);
            if (start == null) {
                outer:
                for (int r = 1; r <= 2; r++) {
                    for (int[] d : DIRS) {
                        Node s = evaluateCached(level, p, fx + d[0] * r, fz + d[1] * r, ref);
                        if (s != null) {
                            start = s;
                            break outer;
                        }
                    }
                }
            }
            if (start == null) {
                finish(t0, 0);
                return null;
            }
            Search s = new Search(level, p, start, tx, tz);
            s.spent = System.nanoTime() - t0;
            return s;
        }

        /** 始めの節。 */
        public Node startNode() {
            return this.start;
        }

        /** これまでに使った時間(ns)。 */
        public long spent() {
            return this.spent;
        }

        /** 今の一番近づける所までの経路(打ち切った印つき)。 */
        public Plan partial() {
            return build(this.closest, false, true, 0L);
        }

        /** 最大 {@code capNanos} だけ進める。終われば経路、まだなら null。 */
        @Nullable
        public Plan run(long capNanos) {
            long t0 = System.nanoTime();
            int done0 = this.expanded;
            Open goal = null;
            boolean finished = false;
            while (true) {
                if (this.open.isEmpty() || this.expanded >= MAX_EXPAND) {
                    finished = true;
                    break;
                }
                // 時間の上限: 取り出す前に見る(取り出した節を捨てない)
                if (this.expanded > done0 && System.nanoTime() - t0 > capNanos) break;   // 毎節見る(4 節ごとだと冷えた節の調べで 0.4 ms はみ出した、11-11)
                Open cur = this.open.poll();
                double bg = this.best.get(key(cur.n.x, cur.n.z));
                if (!Double.isNaN(bg) && cur.g > bg + 1.0E-9D) continue;
                this.expanded++;
                if (cur.n.x == this.tx && cur.n.z == this.tz) {
                    goal = cur;
                    finished = true;
                    break;
                }
                double hc = h(cur.n.x, cur.n.z, this.tx, this.tz);
                if (hc < this.closestH) {
                    this.closestH = hc;
                    this.closest = cur;
                }
                expand(cur);
            }
            long ns = System.nanoTime() - t0;
            this.spent += ns;
            nanos += ns;
            StiltPlanner.expanded += this.expanded - done0;
            maxNanos = Math.max(maxNanos, ns);
            if (!finished) return null;
            calls++;
            return build(goal != null ? goal : this.closest, goal != null, false, this.spent);
        }

        private void expand(Open cur) {
            for (int[] d : DIRS) {
                int nx = cur.n.x + d[0], nz = cur.n.z + d[1];
                if (Math.abs(nx - this.start.x) > RADIUS || Math.abs(nz - this.start.z) > RADIUS) continue;
                Node n = node(this.level, this.p, this.nodes, this.blocked, nx, nz, cur.n.ground);
                if (n == null) continue;
                if (d[0] != 0 && d[1] != 0) {
                    if (node(this.level, this.p, this.nodes, this.blocked, cur.n.x + d[0], cur.n.z, cur.n.ground) == null
                            || node(this.level, this.p, this.nodes, this.blocked, cur.n.x, cur.n.z + d[1], cur.n.ground) == null) continue;
                }
                double db = Math.abs(n.b - cur.n.b);
                if (db > MAX_DB) continue;
                double step = d[0] != 0 && d[1] != 0 ? Math.sqrt(2.0D) : 1.0D;
                // 森が密(足場が少ない)ほど・かがむほど高くつける【仮】
                double crouch = Math.max(0.0D, (this.p.preferHeight - (n.b + this.p.bodyHalf - n.ground)) / (this.p.preferHeight - this.p.minHeight));
                double cost = step * (1.0D + 1.2D * (1.0D - n.open) + 1.5D * crouch) + db * 0.8D;
                double g = cur.g + cost;
                long k = key(nx, nz);
                double old = this.best.get(k);
                if (!Double.isNaN(old) && old <= g) continue;
                this.best.put(k, g);
                this.open.add(new Open(n, g, g + h(nx, nz, this.tx, this.tz), cur));
            }
        }

        private Plan build(Open end, boolean reaches, boolean capped, long ns) {
            List<Node> out = new ArrayList<>();
            for (Open o = end; o != null && o.parent != null; o = o.parent) {
                out.add(0, o.n);
            }
            return new Plan(out, reaches, this.expanded, ns, capped);
        }
    }

    @Nullable
    private static Node node(ServerLevel level, StiltParams p, it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Node> nodes, it.unimi.dsi.fastutil.longs.LongOpenHashSet blocked, int x, int z, double ref) {
        long k = key(x, z);
        Node n = nodes.get(k);
        if (n != null) return n;
        if (blocked.contains(k)) return null;
        n = evaluateCached(level, p, x, z, ref);
        if (n == null) {
            blocked.add(k);
        } else {
            nodes.put(k, n);
        }
        return n;
    }

    private static double h(int x, int z, int tx, int tz) {
        double dx = Math.abs(x - tx), dz = Math.abs(z - tz);
        return Math.max(dx, dz) + (Math.sqrt(2.0D) - 1.0D) * Math.min(dx, dz);
    }

    private static long finish(long t0, int expanded) {
        long ns = System.nanoTime() - t0;
        nanos += ns;
        calls++;
        StiltPlanner.expanded += expanded;
        maxNanos = Math.max(maxNanos, ns);
        return ns;
    }
}
