package com.yayuy.overgrownvegetation.organ;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 器官 1 つの設計図(2026-11-17): {@link OrganShapes} の立体を、向きを付けて「置く順に並べたブロックの列」にしたもの。
 * 純粋な計算なので背景のスレッドで作る({@link OrganWorld})。保存しない(種から作り直せる)。
 *
 * <p>場所は錨(器官の真ん中の地面のいちばん上のブロック)からのずれ。形の {@code G - 1}(絵の地面のいちばん上)を錨の高さに合わせる。
 * 置く順は下から上へ、少しノイズで前線をぎざぎざにする(芽 → 育ち途中 → 完成)。光る筋は支えの面を設計図から決めておく。
 */
public final class OrganBlueprint {

    /** 器官の箱の大きさの上限(芯の持ち主を探すときの箱)。 */
    public static final int MAX_HALF = 40, MAX_UP = 76, MAX_DOWN = 36;

    // 置く材料(OrganShapes の材料をまとめたもの)
    public static final int M_CARVE = 1, M_MOSS = 2, M_LOG = 3, M_GIANT_LOG = 4, M_SAC = 5, M_SAC_WITHERED = 6, M_VEIN = 7,
            M_CORE = 8, M_CRYSTAL = 9, M_PITH = 10, M_CAP = 11, M_ROOT = 12, M_EGG = 13, M_STONE = 14;

    /** 置く順の列。{@link #dx} などで読む。 */
    public final long[] entries;
    /** 芯のずれ(芯のブロックの全部)。 */
    public final List<BlockPos> cores;
    /** 芯の真ん中のずれ。 */
    public final BlockPos coreCenter;
    /** 胞子の口のずれ(塔)。 */
    public final List<BlockPos> mouths;
    /** 結晶のずれ(根晶が生え直す所)。 */
    public final List<BlockPos> crystals;
    /** 入口のずれ(守りの Mob を置く)。 */
    public final BlockPos entrance;
    /** 触手の付け根のずれ(外側の高い所。外へ向く)。 */
    public final List<BlockPos> tentacleBases;

    private OrganBlueprint(long[] entries, List<BlockPos> cores, BlockPos coreCenter, List<BlockPos> mouths, List<BlockPos> crystals,
                           BlockPos entrance, List<BlockPos> tentacleBases) {
        this.entries = entries;
        this.cores = cores;
        this.coreCenter = coreCenter;
        this.mouths = mouths;
        this.crystals = crystals;
        this.entrance = entrance;
        this.tentacleBases = tentacleBases;
    }

    public static int dx(long e) {
        return (int) ((e >>> 40) & 0xff) - 128;
    }

    public static int dy(long e) {
        return (int) ((e >>> 32) & 0xff) - 128;
    }

    public static int dz(long e) {
        return (int) ((e >>> 24) & 0xff) - 128;
    }

    public static int material(long e) {
        return (int) ((e >>> 8) & 0xff);
    }

    /** 光る筋が付く面(ビット = {@link Direction#get3DDataValue})。 */
    public static int faces(long e) {
        return (int) (e & 0x3f);
    }

    private static long pack(int dx, int dy, int dz, int mat, int faces) {
        return ((long) (dx + 128) & 0xff) << 40 | ((long) (dy + 128) & 0xff) << 32 | ((long) (dz + 128) & 0xff) << 24
                | ((long) mat & 0xff) << 8 | (faces & 0x3f);
    }

    /** 形の材料 → 置く材料(0 = 置かない)。 */
    static int mapMaterial(int m) {
        return switch (m) {
            case OrganShapes.CARVE -> M_CARVE;
            case OrganShapes.MOSS, OrganShapes.MOSS2, OrganShapes.LEAF -> M_MOSS;
            case OrganShapes.BARK, OrganShapes.LIANA -> M_LOG;
            case OrganShapes.BARK2 -> M_GIANT_LOG;
            case OrganShapes.SAC, OrganShapes.SACW -> M_SAC;
            case OrganShapes.BURST -> M_SAC_WITHERED;
            case OrganShapes.GLOW -> M_VEIN;
            case OrganShapes.CORE -> M_CORE;
            case OrganShapes.CRYSTAL -> M_CRYSTAL;
            case OrganShapes.PITH -> M_PITH;
            case OrganShapes.CAP -> M_CAP;
            case OrganShapes.ROOT -> M_ROOT;
            case OrganShapes.EGG -> M_EGG;
            case OrganShapes.STONE -> M_STONE;
            default -> 0; // 空気・土・草・シダ・人の印
        };
    }

    private static boolean solid(int m) {
        return m != OrganShapes.AIR && m != OrganShapes.CARVE && m != OrganShapes.GLOW && m != OrganShapes.PLAYER
                && m != OrganShapes.FERN;
    }

    /** 形を作って設計図にする。 */
    public static OrganBlueprint create(OrganKind kind, boolean small, int variant, int seed, int rot) {
        return of(small ? OrganShapes.buildSmall(kind, variant, seed) : OrganShapes.build(kind, variant, seed), rot, seed);
    }

    static OrganBlueprint of(OrganShapes.Model m, int rot, int seed) {
        int cx = m.W / 2, cz = m.D / 2, gy = m.G - 1;
        int n = 0;
        long[] tmp = new long[m.W * m.H * m.D / 4 + 16];
        double[] keys = new double[tmp.length];
        List<BlockPos> cores = new ArrayList<>();
        List<BlockPos> crystals = new ArrayList<>();
        for (int x = 0; x < m.W; x++) {
            for (int y = 0; y < m.H; y++) {
                for (int z = 0; z < m.D; z++) {
                    int raw = m.raw(x, y, z);
                    int mat = mapMaterial(raw);
                    if (mat == 0) continue;
                    int faces = 0;
                    if (mat == M_VEIN) {
                        boolean open = false;
                        for (Direction d : Direction.values()) {
                            int nb = m.raw(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ());
                            if (solid(nb)) faces |= 1 << rotate(d, rot).get3DDataValue();
                            else open = true;
                        }
                        if (faces == 0) continue;               // 宙に浮いた筋は置かない
                        if (!open) { mat = M_MOSS; faces = 0; }  // 埋まった筋は苔に
                    }
                    int ox = x - cx, oz = z - cz, oy = y - gy;
                    int rx = rotX(ox, oz, rot), rz = rotZ(ox, oz, rot);
                    if (n == tmp.length) {
                        tmp = Arrays.copyOf(tmp, n * 2);
                        keys = Arrays.copyOf(keys, n * 2);
                    }
                    tmp[n] = pack(rx, oy, rz, mat, faces);
                    // 下から上へ。前線はノイズでぎざぎざに。光る筋は同じ高さの支えの後
                    keys[n] = oy + 4.0D * OrganShapes.n3(x * .15 + seed % 97, 0.0D, z * .15) + (mat == M_VEIN ? 0.6D : 0.0D)
                            + (mat == M_CARVE ? -0.3D : 0.0D);
                    n++;
                    if (mat == M_CORE) cores.add(new BlockPos(rx, oy, rz));
                    if (mat == M_CRYSTAL) crystals.add(new BlockPos(rx, oy, rz));
                }
            }
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        final double[] k = keys;
        Arrays.sort(order, (a, b) -> Double.compare(k[a], k[b]));
        long[] sorted = new long[n];
        for (int i = 0; i < n; i++) sorted[i] = tmp[order[i]];

        BlockPos coreCenter = BlockPos.ZERO;
        if (m.core != null) {
            int ox = (int) Math.round(m.core[0]) - cx, oz = (int) Math.round(m.core[2]) - cz;
            coreCenter = new BlockPos(rotX(ox, oz, rot), (int) Math.round(m.core[1]) - gy, rotZ(ox, oz, rot));
        }
        List<BlockPos> mouths = new ArrayList<>();
        for (double[] p : m.plumes) {
            int ox = (int) Math.round(p[0]) - cx, oz = (int) Math.round(p[2]) - cz;
            mouths.add(new BlockPos(rotX(ox, oz, rot), (int) Math.round(p[1]) - gy, rotZ(ox, oz, rot)));
        }
        BlockPos entrance = BlockPos.ZERO;
        if (!m.inPaths.isEmpty() && m.inPaths.get(0).length > 0) {
            double[] p = m.inPaths.get(0)[0];
            int ox = (int) Math.round(p[0]) - cx, oz = (int) Math.round(p[2]) - cz;
            entrance = new BlockPos(rotX(ox, oz, rot), Math.max(1, (int) Math.round(p[1]) - gy), rotZ(ox, oz, rot));
        }
        return new OrganBlueprint(sorted, List.copyOf(cores), coreCenter, List.copyOf(mouths), List.copyOf(crystals), entrance,
                tentacleBases(sorted, seed));
    }

    static int rotX(int x, int z, int rot) {
        return switch (rot & 3) {
            case 1 -> -z;
            case 2 -> -x;
            case 3 -> z;
            default -> x;
        };
    }

    static int rotZ(int x, int z, int rot) {
        return switch (rot & 3) {
            case 1 -> x;
            case 2 -> -z;
            case 3 -> -x;
            default -> z;
        };
    }

    static Direction rotate(Direction d, int rot) {
        if (d.getAxis() == Direction.Axis.Y) return d;
        Direction r = d;
        // rotX/rotZ と同じ回り方(x,z) → (-z, x) = 時計回り
        for (int i = 0; i < (rot & 3); i++) r = r.getClockWise();
        return r;
    }

    /** 確かめ用: 材料ごとの数。 */
    public int[] counts() {
        int[] c = new int[16];
        for (long e : entries) c[material(e)]++;
        return c;
    }

    /** 芯のずれをまとめて(テスト用)。 */
    LongArrayList coreKeys(BlockPos anchor) {
        LongArrayList l = new LongArrayList();
        for (BlockPos c : cores) l.add(anchor.offset(c).asLong());
        return l;
    }

    /**
     * 触手の付け根: 幹・苔・根の表面のうち、高さの 35〜85% で真ん中からいちばん遠い所を、互いに 6 ブロック以上離して 5 つまで。
     * 外の空気に面した所だけ(中の部屋から生やさない)。
     */
    static List<BlockPos> tentacleBases(long[] entries, int seed) {
        int maxY = 1;
        for (long e : entries) maxY = Math.max(maxY, dy(e));
        it.unimi.dsi.fastutil.longs.LongOpenHashSet solid = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (long e : entries) {
            int m = material(e);
            if (m != M_CARVE && m != M_VEIN) solid.add(BlockPos.asLong(dx(e), dy(e), dz(e)));
        }
        List<long[]> cands = new ArrayList<>(); // {packed, r2}
        for (long e : entries) {
            int m = material(e);
            if (m != M_LOG && m != M_GIANT_LOG && m != M_MOSS && m != M_ROOT) continue;
            int x = dx(e), y = dy(e), z = dz(e);
            if (y < maxY * 0.35D || y > maxY * 0.85D) continue;
            // 外向きの隣が空いている
            int sx = Integer.signum(x), sz = Integer.signum(z);
            if (solid.contains(BlockPos.asLong(x + sx, y, z)) && solid.contains(BlockPos.asLong(x, y, z + sz))) continue;
            cands.add(new long[]{BlockPos.asLong(x, y, z), (long) x * x + (long) z * z});
        }
        cands.sort((a, b) -> Long.compare(b[1], a[1]));
        List<BlockPos> out = new ArrayList<>();
        java.util.Random r = new java.util.Random(seed);
        int top = Math.min(cands.size(), 400);
        for (int tries = 0; tries < 200 && out.size() < 5 && top > 0; tries++) {
            BlockPos p = BlockPos.of(cands.get(r.nextInt(top))[0]);
            boolean far = true;
            for (BlockPos q : out) if (q.distSqr(p) < 36) far = false;
            if (far) out.add(p);
        }
        return List.copyOf(out);
    }
}
