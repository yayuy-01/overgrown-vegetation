package com.yayuy.overgrownvegetation.base.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 配置計画を、決まった範囲のブロックの並びにする(2026-10-01)。ゲームの設置(世界生成ではチャンクごと、コマンドでは基地まるごと)と
 * プレビューの書き出しが同じこれを通る。
 *
 * <p>範囲の外のブロックを読まない —— 置くものは計画と地形の標本と「その列の自然の地表の高さ」だけで決まるので、
 * チャンクごとに置いても境目がずれない。塀・金網のつながりは範囲を 1 マス広げて作ってから決める({@link Connections})。
 *
 * <p>地面: 区画の和の中は {@link BaseTerrain#target} の高さまで盛るか削り、上を仕上げ({@link Surface})にする。外の
 * {@link BasePlan#CLEAR_MARGIN} マスは外周際のなだらかな面から自然の地表へなじませ、草木を払う(撃てる見通しを取る前線基地の流儀)。
 * <b>外の水の列には一切触らない</b>(ユーザー指摘 2026-10-01「広げると川を貫いて川が消える」)。
 *
 * <p>レベルを上げるときは、古い計画と新しい計画で {@link #columnKey} の違う列と、新しい建物だけを置き直す。
 * つながりのあるブロックは、置き直した列の隣のものも決め直して置く(古い金網が新しい金網へつながるように)。
 */
public final class BaseEmitter {

    /** 草木・雪だけ払う(石や人の建てたものは残す)マス。プレビューでは空気と同じ。 */
    public static final String SOFT_AIR = "~air";
    /** 地面の上の、草木を払う高さ。 */
    public static final int CLEAR_HEIGHT = 20;
    /** 金網の支柱(塀を積む。天辺の有刺鉄線の下まで柱を立てる)。 */
    private static final String FENCE_POST = States.of(Pal.RC_WALL, "up", "true");
    /** 壁へ上がる階段(鉄筋の塔の階段と同じ石レンガ)。 */
    private static final String RAMP_STAIRS = "minecraft:stone_brick_stairs";
    /** 階段の長さの上限(地形が下っていても、これより長くしない)。 */
    private static final int RAMP_MAX = 9;
    /** 柵の切れ目を、望む場所から柵に沿ってずらしてよい長さ(中へ抜けられる所を探す)。 */
    private static final int GAP_SEARCH = 12;

    /** その列の自然の地表(基地の局所座標)。 */
    public interface Natural {
        /** 一番上の固いブロックの y(水の下なら水底)。 */
        int groundY(int x, int z);

        /** 水面の y(水が無ければ {@link #groundY} と同じ)。 */
        int surfaceY(int x, int z);
    }

    public interface Sink {
        void set(int x, int y, int z, String state);
    }

    public interface ColumnFilter {
        boolean test(int x, int z);
    }

    private final BasePlan plan;
    private final BaseTerrain terrain;
    private final Map<Plot, Blueprint> blueprints = new ConcurrentHashMap<>();
    /** 16 × 16 の区画ごとの、近く(法面の幅まで)にかかる建物。 */
    private final Map<Long, List<Plot>> buckets = new ConcurrentHashMap<>();

    public BaseEmitter(BasePlan plan, BaseTerrain terrain) {
        this.plan = plan;
        this.terrain = terrain;
        int reach = BaseTerrain.SLOPE_REACH;
        for (Plot p : plan.plots) {
            for (int bx = (p.x0() - reach) >> 4; bx <= (p.x1() + reach) >> 4; bx++) {
                for (int bz = (p.z0() - reach) >> 4; bz <= (p.z1() + reach) >> 4; bz++) {
                    buckets.computeIfAbsent(key(bx, bz), k -> new ArrayList<>()).add(p);
                }
            }
        }
        // 研究所の連絡通路: 土台は両端の棟の低い方(床は棟の高さに合わせて段で上り下りする。棟はそれぞれの地面に建つ —— 実際の地形では
        // 棟どうしの土台が 11〜22 段違い、高さをそろえると崖ができた。2026-10-02 夜)
        for (Plot p : plan.plots) {
            if (p.kind() == BuildingKind.RESEARCH_WALKWAY) {
                Plot[] ends = walkwayEnds(p);
                if (ends != null) {
                    terrain.pinPad(p, Math.min(terrain.pad(ends[0]), terrain.pad(ends[1])));
                }
            }
        }
    }

    /** 連絡通路 {@code w} の両端の棟 {始めの端(向きの逆), 終わりの端(向きの先)}。片方でも無ければ null。 */
    private Plot[] walkwayEnds(Plot w) {
        Facing f = w.facing();
        int midX = (w.x0() + w.x1()) / 2;
        int midZ = (w.z0() + w.z1()) / 2;
        Plot a;
        Plot b;
        if (f.dx != 0) {
            a = plan.plotAt(f.dx > 0 ? w.x0() - 1 : w.x1() + 1, midZ);
            b = plan.plotAt(f.dx > 0 ? w.x1() + 1 : w.x0() - 1, midZ);
        } else {
            a = plan.plotAt(midX, f.dz > 0 ? w.z0() - 1 : w.z1() + 1);
            b = plan.plotAt(midX, f.dz > 0 ? w.z1() + 1 : w.z0() - 1);
        }
        return a != null && b != null ? new Plot[]{a, b} : null;
    }

    private static long key(int bx, int bz) {
        return ((long) bx << 32) ^ (bz & 0xFFFFFFFFL);
    }

    private List<Plot> near(int x, int z) {
        List<Plot> list = buckets.get(key(x >> 4, z >> 4));
        return list != null ? list : Collections.emptyList();
    }

    public BasePlan plan() {
        return plan;
    }

    public BaseTerrain terrain() {
        return terrain;
    }

    /** 区画の設計図(基地のレベルの姿、向きへ回したもの)。 */
    public Blueprint blueprint(Plot p) {
        return blueprints.computeIfAbsent(p, q -> {
            if (q.kind() == BuildingKind.RESEARCH_WALKWAY) {
                Plot[] ends = walkwayEnds(q);
                if (ends != null) {
                    int base = terrain.pad(q);
                    return Blueprints.walkway(q, terrain.pad(ends[0]) - base, terrain.pad(ends[1]) - base);
                }
            }
            return Blueprints.build(q, plan.level);
        });
    }

    /** 計画の地面の高さ(区画の和の中・建物の足元。外は {@link #outsideTarget})。 */
    public int target(int x, int z) {
        return terrain.target(x, z, near(x, z));
    }

    /** 区画の和の外の地面: 外周際はなだらかな面、{@link BasePlan#CLEAR_MARGIN} マス先で自然の地表になる。 */
    private int outsideTarget(int x, int z, int natural) {
        double t = Math.min(1.0, plan.outDist(x, z) / (double) BasePlan.CLEAR_MARGIN);
        return (int) Math.round(target(x, z) * (1 - t) + natural * t);
    }

    /** 列の地面を計画の高さにするか(区画の和の中と、外に出た建物 —— 塔・正門・検問所 —— の足元)。 */
    private boolean planned(int x, int z) {
        return plan.inside(x, z) || plan.plotAt(x, z) != null;
    }

    /** 列に手を入れるか(区画の和の中・払う幅・外の道・建物の足元)。 */
    private boolean touched(int x, int z) {
        return plan.isClear(x, z) || plan.plotAt(x, z) != null || plan.surfaceAt(x, z) != Surface.NATURAL;
    }

    private static boolean defense(Plot p) {
        return p.kind() == BuildingKind.WATCHTOWER || p.kind() == BuildingKind.GATEHOUSE || p.kind() == BuildingKind.CHECKPOINT;
    }

    /**
     * 列の中身を決める値の要約(0 = 手を入れない列)。レベルを上げるときは、古い計画と新しい計画でこれが違う列だけ置き直す
     * (外周の帯が中へ入った・金網が壁になった・新しい区画がかかった・塔が消えた、など)。値の中身: 役割(外周の外側の列・支柱・内の列・中・
     * 鉄条網・外)、外周と塔・門が壁の時期か、地面の仕上げ、外からの距離、計画の地面の高さ、足元の建物。
     */
    public long columnKey(int x, int z) {
        if (!touched(x, z)) {
            return 0L;
        }
        Plot p = plan.plotAt(x, z);
        int role;
        if (plan.inside(x, z)) {
            role = plan.isPerimeter(x, z) ? (plan.isOuterRow(x, z) ? (plan.isFencePost(x, z) ? 3 : 1) : 2) : 4;
        } else {
            role = plan.isWire(x, z) ? 5 : 6;
        }
        boolean modal = role <= 3 || p != null && defense(p);
        long key = 1L | (long) role << 1 | (modal && plan.wallMode() ? 1L : 0L) << 4
                | (long) plan.surfaceAt(x, z).ordinal() << 5 | (long) Math.min(plan.outDist(x, z), 31) << 9
                | (long) (target(x, z) & 0xFFFF) << 14;
        // 壁の上の設備・壁へ上がる階段・内側の柵(広げると付け替わる。入れないと、中に入った古い柵や階段が置き直されずに残る)
        key = key * 31 + (role <= 3 && plan.wallMode() ? (wallGun(x, z) != null ? 1 : 0) + (wallMount(x, z) != null ? 2 : 0) : 0);
        int[] ramp = access().ramps.get(key(x, z));
        key = key * 31 + (ramp != null ? 1 + ramp[1] + 4L * (ramp[0] & 0xFFFF) : 0);
        key = key * 31 + innerFence(x, z);
        if (p != null) {
            key = key * 1_000_003L + Objects.hash(p.kind(), p.x0(), p.z0(), p.facing());
        }
        return key;
    }

    /**
     * [x0, x1] × [z0, z1](端を含む、局所座標)のブロックを {@code sink} へ流す。
     * {@code columns} が偽の列の地面・道・外周と、{@code plots} が偽の区画は流さない(レベルを上げるときの差分用)。ただし
     * つながりを決めるときは全部を組んで読む。
     */
    public void emit(int x0, int z0, int x1, int z1, Natural natural, ColumnFilter columns, Predicate<Plot> plots, Sink sink) {
        int ax0 = Math.max(x0, plan.minX());
        int az0 = Math.max(z0, plan.minZ());
        int ax1 = Math.min(x1, plan.maxX());
        int az1 = Math.min(z1, plan.maxZ());
        if (ax0 > ax1 || az0 > az1) {
            return;
        }
        // 1 マス広げた範囲で作り、つながりを決めてから内側だけ流す
        int mx0 = ax0 - 1;
        int mz0 = az0 - 1;
        int sx = ax1 - ax0 + 3;
        int sz = az1 - az0 + 3;
        int[] tgt = new int[sx * sz];
        int[] nat = new int[sx * sz];
        int[] wat = new int[sx * sz];
        boolean[] touch = new boolean[sx * sz];
        int yMin = Integer.MAX_VALUE;
        int yMax = Integer.MIN_VALUE;
        for (int i = 0; i < sx; i++) {
            for (int k = 0; k < sz; k++) {
                int x = mx0 + i;
                int z = mz0 + k;
                if (!touched(x, z)) {
                    continue;
                }
                int n = natural.groundY(x, z);
                int w = Math.max(n, natural.surfaceY(x, z));
                int t = planned(x, z) ? target(x, z) : outsideTarget(x, z, n);
                int c = i * sz + k;
                touch[c] = true;
                tgt[c] = t;
                nat[c] = n;
                wat[c] = w;
                yMin = Math.min(yMin, Math.min(n, t));
                yMax = Math.max(yMax, Math.max(w, t) + CLEAR_HEIGHT);
            }
        }
        List<Plot> hits = new ArrayList<>();
        for (Plot p : plan.plots) {
            if (p.x1() >= mx0 && p.x0() <= mx0 + sx - 1 && p.z1() >= mz0 && p.z0() <= mz0 + sz - 1) {
                hits.add(p);
                yMax = Math.max(yMax, terrain.pad(p) + blueprint(p).h);
                yMin = Math.min(yMin, terrain.pad(p));
            }
        }
        if (yMin > yMax) {
            return;
        }
        Voxels v = new Voxels(mx0, yMin, mz0, sx, yMax - yMin + 1, sz);
        // 地面・道・外周・鉄条網
        for (int i = 0; i < sx; i++) {
            for (int k = 0; k < sz; k++) {
                int c = i * sz + k;
                if (!touch[c]) {
                    continue;
                }
                int x = mx0 + i;
                int z = mz0 + k;
                v.mark = columns.test(x, z);
                column(v, x, z, tgt[c], nat[c], wat[c]);
            }
        }
        // 建物
        for (Plot p : hits) {
            Blueprint bp = blueprint(p);
            int pad = terrain.pad(p);
            v.mark = plots.test(p);
            for (int lx = 0; lx < bp.w; lx++) {
                int x = p.x0() + lx;
                if (x < mx0 || x >= mx0 + sx) {
                    continue;
                }
                for (int lz = 0; lz < bp.d; lz++) {
                    int z = p.z0() + lz;
                    if (z < mz0 || z >= mz0 + sz) {
                        continue;
                    }
                    for (int ly = 0; ly < bp.h; ly++) {
                        String s = bp.get(lx, ly, lz);
                        if (!s.isEmpty()) {
                            v.set(x, pad + ly, z, s);
                        }
                    }
                }
            }
        }
        // つながりを決めて、内側だけ下から流す(置き直すマスと、その隣のつながりのあるブロック)
        for (int y = v.y0; y < v.y0 + v.sy; y++) {
            for (int x = ax0; x <= ax1; x++) {
                for (int z = az0; z <= az1; z++) {
                    String s = v.get(x, y, z);
                    if (s.isEmpty()) {
                        continue;
                    }
                    boolean conn = Connections.connects(s);
                    if (!v.marked(x, y, z) && !(conn && (v.marked(x - 1, y, z) || v.marked(x + 1, y, z)
                            || v.marked(x, y, z - 1) || v.marked(x, y, z + 1)))) {
                        continue;
                    }
                    if (conn) {
                        s = Connections.resolve(s, v::get, x, y, z);
                    }
                    sink.set(x, y, z, s);
                }
            }
        }
    }

    /** 1 列の地面: 盛る・削る・仕上げ、外周(金網か壁)、草木払い、鉄条網。外の水の列は触らない。 */
    private void column(Voxels v, int x, int z, int t, int n, int w) {
        boolean inside = plan.inside(x, z);
        Plot plot = plan.plotAt(x, z);
        if (!inside && plot == null && w > n) {
            return; // 外の水(川・湖・海)には触らない
        }
        Surface surface = plan.surfaceAt(x, z);
        boolean perimeter = plan.isPerimeter(x, z);
        boolean wall = perimeter && plan.wallMode();
        String top;
        if (wall) {
            top = Pal.RC;
        } else if (inside || plot != null) {
            top = surface == Surface.NATURAL ? Pal.GRAVEL : surface.top;
        } else {
            top = surface == Surface.NATURAL ? null : surface.top; // 外: 道だけ塗る
        }
        // 盛り土 / 削り
        if (t > n) {
            for (int y = n + 1; y < t; y++) {
                v.set(x, y, z, Pal.DIRT);
            }
            v.set(x, t, z, top != null ? top : Pal.GRASS);
        } else {
            if (top != null || t < n) {
                v.set(x, t, z, top != null ? top : Pal.GRASS);
            }
            for (int y = t + 1; y <= n; y++) {
                v.set(x, y, z, Pal.AIR);
            }
        }
        // 中の水(小さな池・標本のあいだを流れる細い流れ)は埋める
        for (int y = Math.max(t, n) + 1; y <= w; y++) {
            v.set(x, y, z, Pal.AIR);
        }
        // 草木払い
        if (plan.isClear(x, z) || plot != null) {
            for (int y = Math.max(t, w) + 1; y <= t + CLEAR_HEIGHT; y++) {
                if (v.get(x, y, z).isEmpty()) {
                    v.set(x, y, z, SOFT_AIR);
                }
            }
        }
        if (perimeter) {
            if (wall) {
                for (int y = t + 1; y < t + BasePlan.WALL_HEIGHT; y++) {
                    v.set(x, y, z, Pal.RC);
                }
                if (plan.isOuterRow(x, z)) {
                    Facing gun = wallGun(x, z);
                    v.set(x, t + BasePlan.WALL_HEIGHT, z, gun != null ? Pal.gunTurret(gun) : Pal.RC_WALL);
                } else {
                    String mount = wallMount(x, z);
                    if (mount != null) {
                        v.set(x, t + BasePlan.WALL_HEIGHT, z, mount);
                    }
                }
            } else if (plan.isOuterRow(x, z)) {
                String fence = plan.isFencePost(x, z) ? FENCE_POST : Pal.CHAIN_LINK;
                for (int y = t + 1; y <= t + BasePlan.FENCE_HEIGHT; y++) {
                    v.set(x, y, z, fence);
                }
                v.set(x, t + BasePlan.FENCE_HEIGHT + 1, z, Pal.BARBED_WIRE);
            }
        }
        if (plan.isWire(x, z)) {
            v.set(x, t + 1, z, Pal.BARBED_WIRE);
        }
        int fence = innerFence(x, z);
        if (fence > 0) {
            String s = fence == 2 ? FENCE_POST : Pal.CHAIN_LINK;
            for (int y = t + 1; y <= t + BasePlan.FENCE_HEIGHT; y++) {
                v.set(x, y, z, s);
            }
        }
        int[] ramp = access().ramps.get(key(x, z));
        if (ramp != null) {
            for (int y = t + 1; y < ramp[0]; y++) {
                v.set(x, y, z, Pal.RC);
            }
            v.set(x, ramp[0], z, Pal.stairs(RAMP_STAIRS, Facing.values()[ramp[1]], false));
        }
    }

    // ---------- 壁へ上がる階段・内側の柵 ----------

    /**
     * 壁へ上がる階段と内側の柵の切れ目。基地まるごとで 1 回だけ決める(チャンクごとに書き出しても同じになるよう)。
     * ユーザー指摘 2026-10-02「たどり着く経路の無い機関砲台・胞子除去装置」: 壁(レベル 4〜5)へ上がる手段が無かった。
     */
    private static final class Access {
        /** 階段の列 → {段の y, 上る向きの番号}。 */
        final Map<Long, int[]> ramps = new HashMap<>();
        /** 階段の下の端 {x, z, 内の向きの番号}(柵の切れ目を近くに空ける)。 */
        final List<int[]> bottoms = new ArrayList<>();
        /** 内側の柵を空ける列。 */
        final Set<Long> gaps = new HashSet<>();
        /** 置く壁の機関砲台(胸壁の列 → 外の向き)と胞子除去装置(内の列)。階段で上がれる歩廊に面するものだけ。 */
        final Map<Long, Facing> guns = new HashMap<>();
        final Set<Long> mounts = new HashSet<>();
    }

    private volatile Access access;

    private Access access() {
        Access a = access;
        if (a == null) {
            synchronized (this) {
                if (access == null) {
                    Access made = new Access();
                    if (plan.wallMode()) {
                        ramps(made);
                    }
                    gaps(made);
                    access = made;
                }
                a = access;
            }
        }
        return a;
    }

    /**
     * 内側の柵(巡回路と中の砂利の境目、{@link BasePlan#INNER_FENCE} の列)の列なら 1 = 金網・2 = 支柱、でなければ 0。
     * 砂利の上だけ(道・広場・前庭・建物の足元は空ける)、見張り台の扉の前と壁へ上がる階段の下の近くに切れ目。
     */
    public int innerFence(int x, int z) {
        if (!isInnerFence(x, z)) {
            return 0;
        }
        boolean w = isInnerFence(x - 1, z);
        boolean e = isInnerFence(x + 1, z);
        boolean n = isInnerFence(x, z - 1);
        boolean s = isInnerFence(x, z + 1);
        int horizontal = (w ? 1 : 0) + (e ? 1 : 0);
        int vertical = (n ? 1 : 0) + (s ? 1 : 0);
        if (horizontal + vertical != 2) {
            return 2; // 端・枝分かれ・ひとつだけ
        }
        boolean post;
        if (vertical == 0) {
            post = Math.floorMod(x, 6) == 0;
        } else if (horizontal == 0) {
            post = Math.floorMod(z, 6) == 0;
        } else {
            // 斜めの辺の階段の段(BasePlan#isFencePost と同じ数え方)
            int dx = e ? 1 : -1;
            int dz = s ? 1 : -1;
            post = dx * dz > 0 ? Math.floorMod(x - z, 6) == 0 : Math.floorMod(x + z, 6) == 0;
        }
        return post ? 2 : 1;
    }

    private boolean isInnerFence(int x, int z) {
        if (!fenceRow(x, z) || plan.plotAt(x, z) != null) {
            return false;
        }
        Surface s = plan.surfaceAt(x, z);
        return (s == Surface.NATURAL || s == Surface.GRAVEL) && !access().gaps.contains(key(x, z));
    }

    private boolean fenceRow(int x, int z) {
        return plan.inside(x, z) && plan.inDist(x, z) == BasePlan.INNER_FENCE;
    }

    /** 歩廊(壁の内の列の天辺)のマスか。胞子除去装置の載るマスは歩廊を切る。 */
    private boolean walkway(int x, int z) {
        return plan.isPerimeter(x, z) && !plan.isOuterRow(x, z) && !rawWallMount(x, z);
    }

    /** 壁の設備ごとに、その設備の隣に立てる歩廊の区間へ上がる階段が無ければ足す(機関砲台 → 胞子除去装置の順)。 */
    private void ramps(Access a) {
        Map<Long, Integer> seg = new HashMap<>();
        List<int[]> anchors = new ArrayList<>();
        List<int[]> mounts = new ArrayList<>();
        int ids = 0;
        for (int x = plan.minX(); x <= plan.maxX(); x++) {
            for (int z = plan.minZ(); z <= plan.maxZ(); z++) {
                Facing g = rawWallGun(x, z);
                if (g != null) {
                    anchors.add(new int[]{x - g.dx, z - g.dz, x, z, g.ordinal()});
                }
                if (rawWallMount(x, z)) {
                    mounts.add(new int[]{x, z});
                }
                if (!walkway(x, z) || seg.containsKey(key(x, z))) {
                    continue;
                }
                // 歩廊の区間(上下左右につながるマスの組)
                int id = ++ids;
                ArrayDeque<int[]> q = new ArrayDeque<>();
                q.add(new int[]{x, z});
                seg.put(key(x, z), id);
                while (!q.isEmpty()) {
                    int[] c = q.poll();
                    for (Facing f : Facing.values()) {
                        int nx = c[0] + f.dx;
                        int nz = c[1] + f.dz;
                        // 歩廊は列ごとの地面の高さで上下する。2 段以上の段差は歩いて越えられないので、そこで区間を切る
                        // (起伏のきつい実際の地形で、階段から設備まで歩けない区間があった。2026-10-02)
                        if (walkway(nx, nz) && !seg.containsKey(key(nx, nz)) && Math.abs(target(nx, nz) - target(c[0], c[1])) <= 1) {
                            seg.put(key(nx, nz), id);
                            q.add(new int[]{nx, nz});
                        }
                    }
                }
            }
        }
        Set<Integer> served = new HashSet<>();
        for (int[] g : anchors) {
            // 立つ歩廊のマスと砲台の列の高さの差が 1 段まで(それより大きいと、隣に立っても手が届かない)
            Integer s = Math.abs(target(g[0], g[1]) - target(g[2], g[3])) <= 1 ? seg.get(key(g[0], g[1])) : null;
            if (s != null && !served.contains(s) && ramp(a, seg, s, g[0], g[1])) {
                served.add(s);
            }
        }
        for (int[] m : mounts) {
            List<int[]> sides = new ArrayList<>();
            boolean ok = false;
            for (Facing f : Facing.values()) {
                Integer s = Math.abs(target(m[0] + f.dx, m[1] + f.dz) - target(m[0], m[1])) <= 1 ? seg.get(key(m[0] + f.dx, m[1] + f.dz)) : null;
                if (s != null) {
                    ok |= served.contains(s);
                    sides.add(new int[]{m[0] + f.dx, m[1] + f.dz, s});
                }
            }
            for (int i = 0; !ok && i < sides.size(); i++) {
                int[] sd = sides.get(i);
                if (ramp(a, seg, sd[2], sd[0], sd[1])) {
                    served.add(sd[2]);
                    ok = true;
                }
            }
            if (ok) {
                a.mounts.add(key(m[0], m[1]));
            }
        }
        for (int[] g : anchors) {
            Integer s = Math.abs(target(g[0], g[1]) - target(g[2], g[3])) <= 1 ? seg.get(key(g[0], g[1])) : null;
            if (s != null && served.contains(s)) {
                a.guns.put(key(g[2], g[3]), Facing.values()[g[4]]);
            }
        }
    }

    /** 区間 {@code s} の歩廊へ上がる階段を、(tx, tz) に近い所に置く。置けたら真。 */
    private boolean ramp(Access a, Map<Long, Integer> seg, int s, int tx, int tz) {
        ArrayDeque<int[]> q = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        q.add(new int[]{tx, tz});
        seen.add(key(tx, tz));
        while (!q.isEmpty()) {
            int[] c = q.poll();
            if (tryRamp(a, c[0], c[1])) {
                return true;
            }
            for (Facing f : Facing.values()) {
                int nx = c[0] + f.dx;
                int nz = c[1] + f.dz;
                Integer t = seg.get(key(nx, nz));
                if (t != null && t == s && seen.add(key(nx, nz))) {
                    q.add(new int[]{nx, nz});
                }
            }
        }
        return false;
    }

    /**
     * 歩廊のマス (ax, az) の内隣から、壁に沿って下る階段を置けるなら置く。段は歩廊の高さから 1 段ずつ下げ、地面に着いた所で終わる
     * (地形が傾いていても、上の端は歩廊にそろう)。階段のマスは巡回路の、壁に接する列だけ。
     */
    private boolean tryRamp(Access a, int ax, int az) {
        Facing in = null;
        for (Facing f : Facing.values()) {
            if (plan.inside(ax + f.dx, az + f.dz) && plan.inDist(ax + f.dx, az + f.dz) == BasePlan.BAND + 1) {
                if (in != null) {
                    return false; // 角: 内の向きが 1 つに決まらない
                }
                in = f;
            }
        }
        if (in == null) {
            return false;
        }
        int top = target(ax, az) + BasePlan.WALL_HEIGHT; // 歩廊に立つ足の高さ
        for (Facing u : new Facing[]{in.rotateCw(1), in.rotateCw(3)}) {
            List<int[]> cells = new ArrayList<>();
            boolean ok = false;
            for (int k = 0; k <= RAMP_MAX; k++) {
                int rx = ax + in.dx + k * u.dx;
                int rz = az + in.dz + k * u.dz;
                int y = top - 1 - k;
                int t = target(rx, rz);
                if (y <= t) {
                    // 下の端の前: 歩いて乗れる(下の段の天辺 top − k まで 0〜1 段)
                    ok = k > 0 && top - k >= t && plan.inside(rx, rz) && plan.inDist(rx, rz) > BasePlan.BAND
                            && plan.plotAt(rx, rz) == null && !near(a, rx, rz);
                    break;
                }
                if (!rampCell(a, rx, rz, in)) {
                    break;
                }
                cells.add(new int[]{rx, rz, y});
            }
            if (!ok) {
                continue;
            }
            Facing up = u.opposite();
            for (int[] c : cells) {
                a.ramps.put(key(c[0], c[1]), new int[]{c[2], up.ordinal()});
            }
            int[] last = cells.get(cells.size() - 1);
            a.bottoms.add(new int[]{last[0], last[1], in.ordinal()});
            return true;
        }
        return false;
    }

    /** 階段を置ける巡回路のマス: 壁に接する列(壁側の隣が壁の内の列)で、道・建物の足元でなく、ほかの階段から 1 マス離れている。 */
    private boolean rampCell(Access a, int x, int z, Facing in) {
        return plan.inside(x, z) && plan.inDist(x, z) == BasePlan.BAND + 1 && plan.plotAt(x, z) == null
                && plan.surfaceAt(x, z) == Surface.PATH && plan.isPerimeter(x - in.dx, z - in.dz) && !near(a, x, z);
    }

    /** ほかの階段のマスか、その隣(8 方向)か。 */
    private boolean near(Access a, int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (a.ramps.containsKey(key(x + dx, z + dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 見張り台の扉の前と、壁へ上がる階段の下から、まっすぐ中へ進んで柵の列に当たる所に切れ目を空ける。 */
    private void gaps(Access a) {
        List<int[]> wants = new ArrayList<>();
        for (Plot p : plan.plots) {
            if (p.kind() == BuildingKind.WATCHTOWER) {
                wants.add(new int[]{(p.x0() + p.x1()) / 2, (p.z0() + p.z1()) / 2, p.facing().ordinal()});
            }
        }
        for (int[] b : a.bottoms) {
            wants.add(b);
        }
        for (int[] w : wants) {
            Facing f = Facing.values()[w[2]];
            for (int s = 1; s <= 12; s++) {
                int x = w[0] + f.dx * s;
                int z = w[1] + f.dz * s;
                if (plan.inDist(x, z) >= BasePlan.INNER_FENCE) {
                    gapNear(a, x, z);
                    break;
                }
            }
        }
    }

    /** (x, z) の近くの柵の列に、中へ抜けられる切れ目(真ん中 + 柵の列の隣)を空ける。中隣が建物なら柵に沿ってずらす。 */
    private void gapNear(Access a, int x, int z) {
        int[] start = null;
        for (int r = 0; r <= 2 && start == null; r++) {
            for (int dx = -r; dx <= r && start == null; dx++) {
                for (int dz = -r; dz <= r && start == null; dz++) {
                    if (fenceRow(x + dx, z + dz)) {
                        start = new int[]{x + dx, z + dz, 0};
                    }
                }
            }
        }
        if (start == null) {
            return;
        }
        ArrayDeque<int[]> q = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        q.add(start);
        seen.add(key(start[0], start[1]));
        while (!q.isEmpty()) {
            int[] c = q.poll();
            if (innerFree(c[0], c[1])) {
                a.gaps.add(key(c[0], c[1]));
                for (Facing f : Facing.values()) {
                    if (fenceRow(c[0] + f.dx, c[1] + f.dz)) {
                        a.gaps.add(key(c[0] + f.dx, c[1] + f.dz));
                    }
                }
                return;
            }
            if (c[2] >= GAP_SEARCH) {
                continue;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int nx = c[0] + dx;
                    int nz = c[1] + dz;
                    if (fenceRow(nx, nz) && seen.add(key(nx, nz))) {
                        q.add(new int[]{nx, nz, c[2] + 1});
                    }
                }
            }
        }
    }

    /** 柵の列のマスの中隣(1 つ内の列)に、建物の無いマスがあるか。 */
    private boolean innerFree(int x, int z) {
        for (Facing f : Facing.values()) {
            int nx = x + f.dx;
            int nz = z + f.dz;
            if (plan.inDist(nx, nz) == BasePlan.INNER_FENCE + 1 && plan.plotAt(nx, nz) == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 壁の上の設備: 24 マスおきに外へ向けた機関砲台、その真ん中に胞子除去装置(ユーザー指定 2026-10-02)。
     * 外の向きが 1 つに決まらないマス(角・斜めの辺の階段)には置かない。胞子除去装置は内の列の天辺、機関砲台はその外隣の胸壁の列({@link #wallGun})。
     * 隣に立てる歩廊へ上がる階段を置けなかった設備も置かない(短い区間・斜めの辺。届かない設備を作らない)。
     */
    private String wallMount(int x, int z) {
        if (!plan.wallMode() || !access().mounts.contains(key(x, z))) {
            return null;
        }
        Facing out = outward(x, z);
        return out == null ? null : Pal.sporePurifier(out.opposite());
    }

    /** 壁の機関砲台を置く胸壁の列のマスなら外の向き、でなければ null({@link #rawWallGun} のうち、階段で上がれる歩廊に面するもの)。 */
    public Facing wallGun(int x, int z) {
        return plan.wallMode() ? access().guns.get(key(x, z)) : null;
    }

    /** 階段の有無を見る前の、胞子除去装置の場所の決まり(内の列で x + z ≡ 12、外の向きが 1 つ)。 */
    private boolean rawWallMount(int x, int z) {
        return Math.floorMod(x + z, 24) == 12 && plan.isPerimeter(x, z) && !plan.isOuterRow(x, z) && outward(x, z) != null;
    }

    /**
     * 階段の有無を見る前の、機関砲台の場所の決まり。砲台の位置は内の列で 24 マスおき(x + z ≡ 0)、置くのはその外隣。
     * 内の列に置くと目の前の胸壁(高さ 1.5)が射線をふさぎ、壁の外の地上を撃てなかった(ユーザー指摘 2026-10-02「壁の上だとほとんど撃てない」)。
     */
    private Facing rawWallGun(int x, int z) {
        if (!plan.wallMode() || !plan.isOuterRow(x, z) || !plan.isPerimeter(x, z)) {
            return null;
        }
        for (Facing f : Facing.values()) {
            int ix = x - f.dx;
            int iz = z - f.dz;
            if (plan.isPerimeter(ix, iz) && !plan.isOuterRow(ix, iz) && Math.floorMod(ix + iz, 24) == 0 && outward(ix, iz) == f) {
                return f;
            }
        }
        return null;
    }

    /** 内の列のマスの外の向き(外側の列の隣が 1 つだけのとき)。決まらなければ null。 */
    private Facing outward(int x, int z) {
        Facing out = null;
        for (Facing f : Facing.values()) {
            if (plan.isOuterRow(x + f.dx, z + f.dz) && plan.isPerimeter(x + f.dx, z + f.dz)) {
                if (out != null) {
                    return null;
                }
                out = f;
            }
        }
        return out;
    }

    /** 範囲のブロックの入れ物(未設定 = 触らない)と、マスごとの「置き直す」印。 */
    static final class Voxels {
        final int x0;
        final int y0;
        final int z0;
        final int sx;
        final int sy;
        final int sz;
        private final short[] cells;
        private final boolean[] marks;
        private final List<String> palette = new ArrayList<>();
        private final Map<String, Short> index = new java.util.HashMap<>();
        /** これから書くマスに付ける印。 */
        boolean mark = true;

        Voxels(int x0, int y0, int z0, int sx, int sy, int sz) {
            this.x0 = x0;
            this.y0 = y0;
            this.z0 = z0;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.cells = new short[sx * sy * sz];
            this.marks = new boolean[sx * sy * sz];
            palette.add("");
            index.put("", (short) 0);
        }

        private int idx(int x, int y, int z) {
            int i = x - x0;
            int j = y - y0;
            int k = z - z0;
            if (i < 0 || j < 0 || k < 0 || i >= sx || j >= sy || k >= sz) {
                return -1;
            }
            return (j * sz + k) * sx + i;
        }

        void set(int x, int y, int z, String s) {
            int i = idx(x, y, z);
            if (i < 0) {
                return;
            }
            Short p = index.get(s);
            if (p == null) {
                p = (short) palette.size();
                palette.add(s);
                index.put(s, p);
            }
            cells[i] = p;
            marks[i] = mark;
        }

        String get(int x, int y, int z) {
            int i = idx(x, y, z);
            return i < 0 ? "" : palette.get(cells[i]);
        }

        boolean marked(int x, int y, int z) {
            int i = idx(x, y, z);
            return i >= 0 && marks[i];
        }
    }
}
