package com.yayuy.overgrownvegetation.base.road;

import com.yayuy.overgrownvegetation.data.BaseRoadConfig;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.LongPredicate;

/**
 * 同盟軍の道筋を探す(順 2 の着手順 7、2026-10-08)。ワールドを読まない計算(地形はノイズ)なので別スレッドで回せる。
 *
 * <p><b>ユーザー指定(10-01)</b>: 山・崖・急勾配を避けて緩い坂にする。
 *
 * <p>Claude 仮決め:
 * <ol>
 *     <li>8 ブロックおきの格子で A*(格子の原点は出発点 = 正門の道の先)。隣の点との高さの差が {@code maxStepRise} を超える所は崖として通らない。
 *     坂の決まり(4 ブロックで 1 段)より急な分は重く数える。ほかの基地(浄化圏の箱 + 鉄条網の外まで)・過繁茂の守られていないチャンクには点を置かない。</li>
 *     <li>水の点には立てない。陸から陸へ、間が水だけで {@code maxBridge} ブロックまでなら橋で渡る(3 倍に重く数える)。</li>
 *     <li>見つかった折れ線の角を 2 回丸め(両端の 2 点はそのまま —— 正門の道からまっすぐ出る)、1 ブロックおきに並べ直す。</li>
 *     <li>高さ: 各点の目標(陸 = 地面の上、水 = 水面の上)から、坂の上限を守る「全部切り通し」と「全部盛り土」の 2 本の線を作り、その真ん中を取る
 *     (切り通しと盛り土が釣り合う)。水の上は水面より下げない。半ブロックに丸める(ハーフで半段)。盛り土が {@code maxFill} を超える点は橋(下を埋めず橋脚)。</li>
 * </ol>
 */
public final class RoadPlanner {

    /** 格子の間隔(ブロック)。 */
    public static final int STEP = 8;
    /** 1 回に調べる格子の点の上限(見つからなければ諦める)。 */
    private static final int MAX_EXPANSIONS = 150_000;
    /** 橋の重み。 */
    private static final double BRIDGE_FACTOR = 3.0D;
    /** 坂の上限を超えた高さ 1 あたりの重み(ブロック)。 */
    private static final double STEEP_PENALTY = 6.0D;
    /** 印: 橋(下を埋めない)。 */
    public static final byte BRIDGE = 1;

    /**
     * 道筋を探す材料(サーバースレッドで作る写し)。
     *
     * @param start     出発点(正門の道の先の 1 つ外)。向き {@code (startDx, startDz)} は外へ向かう
     * @param obstacles 点を置かない箱 {x0, z0, x1, z1}(世界の座標、端を含む)
     * @param cutChunk  切れたチャンク(過繁茂で守られていない)。{@link ChunkPos#asLong} の鍵
     */
    public record Request(int startX, int startZ, int startDx, int startDz,
                          int goalX, int goalZ, int goalDx, int goalDz,
                          List<int[]> obstacles, LongPredicate cutChunk, RoadTerrain terrain, BaseRoadConfig cfg,
                          int startTop, int goalTop) {

        /** 両端の高さを知らない(ノイズの地形だけで決める)材料。 */
        public Request(int startX, int startZ, int startDx, int startDz, int goalX, int goalZ, int goalDx, int goalDz,
                       List<int[]> obstacles, LongPredicate cutChunk, RoadTerrain terrain, BaseRoadConfig cfg) {
            this(startX, startZ, startDx, startDz, goalX, goalZ, goalDx, goalDz, obstacles, cutChunk, terrain, cfg, UNKNOWN, UNKNOWN);
        }
    }

    /** {@link Request#startTop} / {@link Request#goalTop} が分からない印。 */
    public static final int UNKNOWN = Integer.MIN_VALUE;

    /**
     * 道の真ん中の線(1 ブロックおき)。{@code xs8 / zs8} は 1/8 ブロック単位、{@code h2} は路面の天辺の高さの 2 倍(奇数ならハーフの上)、
     * {@code flags} は {@link #BRIDGE}。
     */
    public record Plan(int[] xs8, int[] zs8, int[] h2, byte[] flags) {
        public int length() {
            return xs8.length;
        }
    }

    /** 探した結果: 道筋(見つからなければ null)と、見つからなかった訳。 */
    public record Outcome(Plan plan, String failure) {
    }

    /** 道筋を探す。見つからなければ null。 */
    public static Plan plan(Request r) {
        return planWithReason(r).plan();
    }

    /** 道筋を探す(見つからなかった訳つき)。 */
    public static Outcome planWithReason(Request r) {
        String[] why = new String[1];
        List<double[]> nodes = search(r, why);
        if (nodes == null) {
            return new Outcome(null, why[0] != null ? why[0] : "道筋が見つからない");
        }
        List<double[]> line = new ArrayList<>();
        line.add(new double[]{r.startX() + 0.5D, r.startZ() + 0.5D});
        line.addAll(nodes);
        line.add(new double[]{r.goalX() + 0.5D, r.goalZ() + 0.5D});
        for (int i = 0; i < 2; i++) {
            line = chaikin(line);
        }
        List<double[]> pts = resample(line);
        return new Outcome(profile(pts, r), null);
    }

    // ---------- 格子の A* ----------

    private static long key(int gx, int gz) {
        return ((long) gx << 32) | (gz & 0xFFFFFFFFL);
    }

    /** 格子の点の世界の座標: 原点 = 出発点から外へ 1 歩。 */
    private record Grid(int ox, int oz) {
        int wx(int gx) {
            return ox + gx * STEP;
        }

        int wz(int gz) {
            return oz + gz * STEP;
        }
    }

    /** 点の状態: 0 = まだ・1 = 陸・2 = 水・3 = 通れない。 */
    private static byte classify(Request r, Grid g, int gx, int gz, Long2ByteOpenHashMap kinds, Long2IntOpenHashMap heights,
                                 int[] bounds, boolean endpoint) {
        long k = key(gx, gz);
        byte v = kinds.get(k);
        if (v != 0) {
            return v;
        }
        int x = g.wx(gx);
        int z = g.wz(gz);
        if (x < bounds[0] || x > bounds[2] || z < bounds[1] || z > bounds[3]) {
            v = 3;
        } else if (!endpoint && (r.cutChunk().test(ChunkPos.asLong(x >> 4, z >> 4)) || inObstacle(r.obstacles(), x, z))) {
            v = 3;
        } else {
            int ground = r.terrain().ground(x, z);
            heights.put(k, ground);
            v = r.terrain().surface(x, z) > ground ? (byte) 2 : (byte) 1;
        }
        kinds.put(k, v);
        return v;
    }

    private static boolean inObstacle(List<int[]> obstacles, int x, int z) {
        for (int[] b : obstacles) {
            if (x >= b[0] && x <= b[2] && z >= b[1] && z <= b[3]) {
                return true;
            }
        }
        return false;
    }

    private record Open(long key, double f) {
    }

    /**
     * 端の格子の点: 正門の道の先の 1 歩外が陸ならそこ、水なら {@code maxBridge} ブロック以内でいちばん近い陸の点(正門が水に面した基地 —— 最初の区間が橋になる)。
     * 見つからなければ null。
     */
    private static int[] endpoint(Request r, Grid g, int gx, int gz, Long2ByteOpenHashMap kinds, Long2IntOpenHashMap heights, int[] bounds) {
        if (classify(r, g, gx, gz, kinds, heights, bounds, true) == 1) {
            return new int[]{gx, gz};
        }
        int reach = Math.max(1, r.cfg().maxBridge / STEP + 1);
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > reach + 1e-9 || d >= bestD) {
                    continue;
                }
                if (classify(r, g, gx + dx, gz + dz, kinds, heights, bounds, false) == 1) {
                    best = new int[]{gx + dx, gz + dz};
                    bestD = d;
                }
            }
        }
        return best;
    }

    /** 格子の点の列(世界の座標、出発点の外の 1 歩 〜 行き先の外の 1 歩)。見つからなければ null({@code why[0]} に訳)。 */
    private static List<double[]> search(Request r, String[] why) {
        BaseRoadConfig cfg = r.cfg();
        Grid g = new Grid(r.startX() + r.startDx() * STEP, r.startZ() + r.startDz() * STEP);
        int goalWx = r.goalX() + r.goalDx() * STEP;
        int goalWz = r.goalZ() + r.goalDz() * STEP;
        int ggx = Math.round((goalWx - g.ox()) / (float) STEP);
        int ggz = Math.round((goalWz - g.oz()) / (float) STEP);
        double dist = Math.hypot(goalWx - g.ox(), goalWz - g.oz());
        int margin = (int) Math.max(128, dist / 2);
        int[] bounds = {Math.min(g.ox(), goalWx) - margin, Math.min(g.oz(), goalWz) - margin,
                Math.max(g.ox(), goalWx) + margin, Math.max(g.oz(), goalWz) + margin};

        Long2ByteOpenHashMap kinds = new Long2ByteOpenHashMap();
        Long2IntOpenHashMap heights = new Long2IntOpenHashMap();
        Long2DoubleOpenHashMap best = new Long2DoubleOpenHashMap();
        best.defaultReturnValue(Double.MAX_VALUE);
        Long2LongOpenHashMap from = new Long2LongOpenHashMap();
        PriorityQueue<Open> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));

        int[] s0 = endpoint(r, g, 0, 0, kinds, heights, bounds);
        if (s0 == null) {
            why[0] = "出発する基地の正門の先が広い水(" + r.cfg().maxBridge + " ブロック以内に陸が無い)";
            return null;
        }
        int[] g0 = endpoint(r, g, ggx, ggz, kinds, heights, bounds);
        if (g0 == null) {
            why[0] = "行き先の基地の正門の先が広い水(" + r.cfg().maxBridge + " ブロック以内に陸が無い)";
            return null;
        }
        ggx = g0[0];
        ggz = g0[1];
        long start = key(s0[0], s0[1]);
        long goal = key(ggx, ggz);
        kinds.put(goal, (byte) 1); // 行き先は箱の中でもよい(classify の endpoint と同じ扱い)
        best.put(start, 0.0D);
        open.add(new Open(start, h(s0[0], s0[1], ggx, ggz)));
        double flatRise = (double) STEP / cfg.slopeRun;
        int maxBridgeSteps = 1 + (int) Math.ceil(cfg.maxBridge / (double) STEP);
        int expansions = 0;
        while (!open.isEmpty()) {
            Open cur = open.poll();
            long ck = cur.key();
            int cx = (int) (ck >> 32);
            int cz = (int) ck;
            double cg = best.get(ck);
            if (cur.f() > cg + h(cx, cz, ggx, ggz) + 1e-6) {
                continue; // 古い候補
            }
            if (ck == goal) {
                return reconstruct(g, from, start, goal);
            }
            if (++expansions > MAX_EXPANSIONS) {
                why[0] = "探す点が多すぎた(" + MAX_EXPANSIONS + ")";
                return null;
            }
            int ch = heights.get(ck);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    double len = (dx != 0 && dz != 0) ? Math.sqrt(2.0D) : 1.0D;
                    for (int k = 1; k <= maxBridgeSteps; k++) {
                        int nx = cx + dx * k;
                        int nz = cz + dz * k;
                        boolean endpoint = key(nx, nz) == goal;
                        byte kind = classify(r, g, nx, nz, kinds, heights, bounds, endpoint);
                        if (kind == 3) {
                            break;
                        }
                        if (kind == 2) {
                            if (k * len * STEP > cfg.maxBridge + STEP) {
                                break; // 橋で渡れない広さ
                            }
                            continue; // 水: もう 1 歩先の陸を探す
                        }
                        // 陸に着いた(k = 1 なら普通の 1 歩、k > 1 なら橋)
                        int nh = heights.get(key(nx, nz));
                        int rise = Math.abs(nh - ch);
                        if (rise > cfg.maxStepRise * k * len) {
                            break; // 崖
                        }
                        if (k == 1 && dx != 0 && dz != 0
                                && !(passable(r, g, cx + dx, cz, ch, kinds, heights, bounds) && passable(r, g, cx, cz + dz, ch, kinds, heights, bounds))) {
                            break; // 斜めの 1 歩は、両脇の点が通れるときだけ(崖の台地・基地の箱の角をかすめない)
                        }
                        double run = k * len * STEP;
                        double cost = run * (k > 1 ? BRIDGE_FACTOR : 1.0D)
                                + Math.max(0.0D, rise - flatRise * k * len) * STEEP_PENALTY;
                        long nk = key(nx, nz);
                        double ng = cg + cost;
                        if (ng < best.get(nk)) {
                            best.put(nk, ng);
                            from.put(nk, ck);
                            open.add(new Open(nk, ng + h(nx, nz, ggx, ggz)));
                        }
                        break;
                    }
                }
            }
        }
        why[0] = "道筋が見つからない(崖・広い水・ほかの基地・汚染に阻まれるか、探す範囲の外)";
        return null;
    }

    /**
     * 格子の点 {@code (gx, gz)} へ、高さ {@code fromHeight} の隣から 1 歩で入れるか(陸で、崖でない)。斜めの 1 歩の両脇を見るのに使う
     * (点だけ見ると、斜めの 1 歩が崖の台地・基地の箱の角を横切る。両脇は格子の点なので、地形を新しく読むことはほとんど無い)。
     */
    private static boolean passable(Request r, Grid g, int gx, int gz, int fromHeight, Long2ByteOpenHashMap kinds, Long2IntOpenHashMap heights,
                                    int[] bounds) {
        if (classify(r, g, gx, gz, kinds, heights, bounds, false) != 1) {
            return false;
        }
        return Math.abs(heights.get(key(gx, gz)) - fromHeight) <= r.cfg().maxStepRise;
    }

    private static double h(int gx, int gz, int ggx, int ggz) {
        return Math.hypot(ggx - gx, ggz - gz) * STEP;
    }

    private static List<double[]> reconstruct(Grid g, Long2LongOpenHashMap from, long start, long goal) {
        List<double[]> out = new ArrayList<>();
        long k = goal;
        while (true) {
            int gx = (int) (k >> 32);
            int gz = (int) k;
            out.add(0, new double[]{g.wx(gx) + 0.5D, g.wz(gz) + 0.5D});
            if (k == start) {
                break;
            }
            k = from.get(k);
        }
        return out;
    }

    // ---------- 折れ線を丸めて並べ直す ----------

    /** 角を丸める(Chaikin)。両端の 2 点は動かさない(正門の道からまっすぐ出る)。 */
    static List<double[]> chaikin(List<double[]> in) {
        if (in.size() < 4) {
            return in;
        }
        List<double[]> out = new ArrayList<>();
        out.add(in.get(0));
        out.add(in.get(1));
        for (int i = 1; i < in.size() - 2; i++) {
            double[] a = in.get(i);
            double[] b = in.get(i + 1);
            out.add(new double[]{0.75D * a[0] + 0.25D * b[0], 0.75D * a[1] + 0.25D * b[1]});
            out.add(new double[]{0.25D * a[0] + 0.75D * b[0], 0.25D * a[1] + 0.75D * b[1]});
        }
        out.add(in.get(in.size() - 2));
        out.add(in.get(in.size() - 1));
        return out;
    }

    /** 1 ブロックおきに並べ直す(最後の点は必ず入れる)。 */
    static List<double[]> resample(List<double[]> line) {
        List<double[]> out = new ArrayList<>();
        out.add(line.get(0));
        double carry = 0.0D;
        for (int i = 0; i + 1 < line.size(); i++) {
            double[] a = line.get(i);
            double[] b = line.get(i + 1);
            double seg = Math.hypot(b[0] - a[0], b[1] - a[1]);
            double t = 1.0D - carry;
            while (t <= seg) {
                double u = t / seg;
                out.add(new double[]{a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u});
                t += 1.0D;
            }
            carry = seg - (t - 1.0D);
        }
        double[] last = line.get(line.size() - 1);
        double[] tail = out.get(out.size() - 1);
        if (Math.hypot(last[0] - tail[0], last[1] - tail[1]) > 0.3D) {
            out.add(last);
        }
        return out;
    }

    // ---------- 高さ ----------

    static Plan profile(List<double[]> pts, Request r) {
        BaseRoadConfig cfg = r.cfg();
        int n = pts.size();
        double[] target = new double[n];
        double[] floor = new double[n];
        int[] ground = new int[n];
        boolean[] water = new boolean[n];
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) {
            int x = (int) Math.floor(pts.get(i)[0]);
            int z = (int) Math.floor(pts.get(i)[1]);
            ground[i] = r.terrain().ground(x, z);
            int surface = r.terrain().surface(x, z);
            water[i] = surface > ground[i];
            raw[i] = (water[i] ? surface : ground[i]) + 1.0D;
            floor[i] = water[i] ? surface + 1.0D : Double.NEGATIVE_INFINITY;
        }
        // 目標は前後 2 点との中央値(丸めた角が崖を 1〜2 ブロックかすめても、道が盛り上がらない。1 ブロックの凸凹も均す)
        for (int i = 0; i < n; i++) {
            target[i] = median(raw, i - 2, i + 2);
        }
        double rate = 1.0D / cfg.slopeRun;
        double[] lowest = target.clone();  // 坂を守って、どこも目標以下(全部切り通し)
        double[] highest = target.clone(); // 坂を守って、どこも目標以上(全部盛り土)
        for (int i = 1; i < n; i++) {
            lowest[i] = Math.min(lowest[i], lowest[i - 1] + rate);
            highest[i] = Math.max(highest[i], highest[i - 1] - rate);
        }
        for (int i = n - 2; i >= 0; i--) {
            lowest[i] = Math.min(lowest[i], lowest[i + 1] + rate);
            highest[i] = Math.max(highest[i], highest[i + 1] - rate);
        }
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            y[i] = Math.max((lowest[i] + highest[i]) / 2.0D, floor[i]);
        }
        // 水面より下げなかった所の坂を直す(持ち上げるだけ)
        for (int i = 1; i < n; i++) {
            y[i] = Math.max(y[i], y[i - 1] - rate);
        }
        for (int i = n - 2; i >= 0; i--) {
            y[i] = Math.max(y[i], y[i + 1] - rate);
        }
        // 両端は正門の道の先の本当の高さにそろえる(分かっているとき)。そこから坂の上限の円錐の中へ収める
        // (ノイズの地形と、建てた基地の正門の道の高さは数ブロック違うことがある —— 段差を作らない)
        if (r.startTop() != UNKNOWN) {
            for (int i = 0; i < n; i++) {
                y[i] = Math.max(r.startTop() - rate * i, Math.min(r.startTop() + rate * i, y[i]));
            }
        }
        if (r.goalTop() != UNKNOWN) {
            for (int i = 0; i < n; i++) {
                int d = n - 1 - i;
                y[i] = Math.max(r.goalTop() - rate * d, Math.min(r.goalTop() + rate * d, y[i]));
            }
        }
        int[] xs8 = new int[n];
        int[] zs8 = new int[n];
        int[] h2 = new int[n];
        byte[] flags = new byte[n];
        for (int i = 0; i < n; i++) {
            xs8[i] = (int) Math.round(pts.get(i)[0] * 8.0D);
            zs8[i] = (int) Math.round(pts.get(i)[1] * 8.0D);
            h2[i] = (int) Math.round(y[i] * 2.0D);
            double fill = h2[i] / 2.0D - (ground[i] + 1.0D);
            if (water[i] || fill > cfg.maxFill) {
                flags[i] |= BRIDGE;
            }
        }
        return new Plan(xs8, zs8, h2, flags);
    }

    /** {@code v[from..to]}(端は切り詰める)の中央値。 */
    private static double median(double[] v, int from, int to) {
        int a = Math.max(0, from);
        int b = Math.min(v.length - 1, to);
        double[] w = java.util.Arrays.copyOfRange(v, a, b + 1);
        java.util.Arrays.sort(w);
        return w[w.length / 2];
    }

    private RoadPlanner() {
    }
}
