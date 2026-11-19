package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.worldgen.fusion.FusionTable;
import com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 構造物と森の融合の器官(2026-11-19 段 1。ユーザー案「蔦の触手が建物に巻き付いて覆う・側面や斜めに生える」、試作 structure-fusion-proto.html)。
 *
 * <p>小さい版の器官が構造物(地表に出ている部品)のそばに建つとき、ふつうの形の代わりに、試作と同じ「軸で組み立てる形」を世界を見て作る:
 * <ol>
 *   <li>根元: 壁の側面・屋根の上・そばの地面(重みは部品の大きさの段ごと。{@code fusion.anchors})。入口から {@code doorClear} 離す。</li>
 *   <li>形: 根元の面の向きから {@code tilt} 度だけ起こした軸に、柄・袋・芯。建物のブロックのマスは切れる(置かない)。</li>
 *   <li>脈: 根元のまわりを這う光る筋(壁際の空気のマスに {@code organ_vein})と覆い。</li>
 *   <li>蔦の触手: 袋から一番近い壁まで宙を渡り、壁に沿って巻きつく(3 本に 1 本は垂れる)。触れた壁を覆う。</li>
 * </ol>
 * 置くのは空気(と置き換えられる草花)のマスだけ。入口の前後の通り道(扉と、外から 2 マスの高さで歩いて入れる壁の穴)と、屋根の下の部屋の中には置かない。
 * 覆いは置き換えない記録({@code OvergrownLayerData#cover})。新しく覆えたマスだけ器官が覚え、枯れたら剥がす。
 *
 * <p>できた列は {@link ForestOrgans.Organ#fusion} に保存する(世界を見て作るので、作り直すと形が変わるため)。
 */
public final class OrganFusion {
    private OrganFusion() {
    }

    public enum Result {
        /** まだ読み込まれていない所がある(次の tick にまた見る)。 */
        WAIT,
        /** 構造物は近くに無い。ふつうの器官。 */
        NORMAL,
        /** 融合の器官にした。 */
        FUSED,
        /** 建てない(大きい版が構造物にかかる・融合の根元が見つからない)。 */
        CANCEL
    }

    /** 大きい版の器官の円の半径の目安(形の外周 + 払う余白。実物は 25〜35)。 */
    static final int LARGE_REACH = 36;
    /** 読み込まれない所を待つ上限(tick)。過ぎたら建てない。 */
    static final int WAIT_LIMIT = 1200;
    /** 建物らしい部品とみなす建材の数【仮】。 */
    static final int BUILDING_BLOCKS = 24;
    /** 根元を付けられる建物の部品が無い印。 */
    static final long[] NO_BUILDING = new long[0];

    /**
     * 芽を置く前に 1 回(高さが決まった後)。{@code o.fusion} を埋めるのは {@link Result#FUSED} のときだけ。
     */
    public static Result decide(ServerLevel level, ForestOrgans.Organ o) {
        FusionTable t = StructureFusion.table();
        if (!t.enabled) return Result.NORMAL;
        int r = o.small ? t.searchRadius : LARGE_REACH;
        int reach = r + (o.small ? t.vineReach + 4 : 0);
        for (int cx = (o.x - reach) >> 4; cx <= (o.x + reach) >> 4; cx++) {
            for (int cz = (o.z - reach) >> 4; cz <= (o.z + reach) >> 4; cz++) {
                if (!OrganWorld.ready(level, cx, cz)) return waitOrGiveUp(o);
            }
        }
        BoundingBox box = new BoundingBox(o.x - r, o.y - 12, o.z - r, o.x + r, o.y + (o.small ? 40 : OrganBlueprint.MAX_UP), o.z + r);
        StructureFusion.Query q = StructureFusion.near(level, box);
        if (q.unknown()) return waitOrGiveUp(o);
        if (q.hits().isEmpty()) return Result.NORMAL;
        if (!o.small) return Result.CANCEL; // 大きい版は構造物にかかる升を避ける(全体管理の決め C)
        long[] plan = plan(level, o, q.hits());
        if (plan == NO_BUILDING) return Result.NORMAL;
        if (plan == null) return Result.CANCEL;
        o.fusion = plan;
        return Result.FUSED;
    }

    private static Result waitOrGiveUp(ForestOrgans.Organ o) {
        return ++o.fusionWait > WAIT_LIMIT ? Result.CANCEL : Result.WAIT;
    }

    // ---------------------------------------------------------------- 形を作る

    /** 世界を見る手伝い(主スレッドで 1 回だけ使う)。 */
    static final class Ctx {
        final ServerLevel level;
        final List<StructureFusion.Hit> hits;
        final FusionTable t;
        final LongOpenHashSet planned = new LongOpenHashSet();
        final LongOpenHashSet reserved = new LongOpenHashSet();
        final List<BlockPos> doors = new ArrayList<>();
        final Long2ByteOpenHashMap structCache = new Long2ByteOpenHashMap();
        final Long2ByteOpenHashMap interiorCache = new Long2ByteOpenHashMap();
        final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        /** 部屋の中にも置けるか(構造物の木の幹・根。葉は置く側で部屋の中を外す)。器官は false。 */
        boolean allowInterior;

        Ctx(ServerLevel level, List<StructureFusion.Hit> hits, FusionTable t) {
            this.level = level;
            this.hits = hits;
            this.t = t;
        }

        boolean inPiece(int x, int y, int z) {
            for (StructureFusion.Hit h : hits) {
                BoundingBox b = h.piece();
                if (x >= b.minX() && x <= b.maxX() && y >= b.minY() && y <= b.maxY() && z >= b.minZ() && z <= b.maxZ()) return true;
            }
            return false;
        }

        @Nullable
        BoundingBox pieceAt(int x, int y, int z) {
            for (StructureFusion.Hit h : hits) {
                BoundingBox b = h.piece();
                if (x >= b.minX() && x <= b.maxX() && y >= b.minY() && y <= b.maxY() && z >= b.minZ() && z <= b.maxZ()) return b;
            }
            return null;
        }

        boolean loaded(int x, int y, int z) {
            return y > level.getMinBuildHeight() && y < level.getMaxBuildHeight() - 1 && level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null;
        }

        BlockState state(int x, int y, int z) {
            return level.getBlockState(m.set(x, y, z));
        }

        /** 建物のブロック(部品の箱の中の、空気でも置き換えられる物でもないブロック)。 */
        boolean struct(int x, int y, int z) {
            long k = BlockPos.asLong(x, y, z);
            byte c = structCache.getOrDefault(k, (byte) -1);
            if (c >= 0) return c == 1;
            boolean v = false;
            if (inPiece(x, y, z) && loaded(x, y, z)) {
                BlockState s = state(x, y, z);
                v = !s.isAir() && !s.canBeReplaced() && !natural(s);
            }
            structCache.put(k, (byte) (v ? 1 : 0));
            return v;
        }

        /** 地面・地形のブロック(部品の箱に入っていても建物と数えない。村の道の部品は地面ごと箱に入る)。 */
        static boolean natural(BlockState s) {
            return StructureFusion.isTerrain(s);
        }

        /** 建物らしい部品か(建材が {@value #BUILDING_BLOCKS} 個以上)。道・畑の部品には根元を付けない。 */
        boolean building(BoundingBox b) {
            int n = 0;
            for (int x = b.minX(); x <= b.maxX(); x++) {
                for (int z = b.minZ(); z <= b.maxZ(); z++) {
                    for (int y = b.minY(); y <= b.maxY(); y++) {
                        if (struct(x, y, z) && ++n >= BUILDING_BLOCKS) return true;
                    }
                }
            }
            return false;
        }

        /** 部屋の中(部品の箱の中で、真上の箱の中に建物のブロックがある)。森を茂らせない。 */
        boolean interior(int x, int y, int z) {
            long k = BlockPos.asLong(x, y, z);
            byte c = interiorCache.getOrDefault(k, (byte) -1);
            if (c >= 0) return c == 1;
            boolean v = false;
            BoundingBox b = pieceAt(x, y, z);
            if (b != null) {
                for (int yy = y + 1; yy <= b.maxY(); yy++) {
                    if (struct(x, yy, z)) {
                        v = true;
                        break;
                    }
                }
            }
            interiorCache.put(k, (byte) (v ? 1 : 0));
            return v;
        }

        /** 森の物を置いてよいマス(空気・置き換えられる草花・水なし・予約と部屋の外・まだ計画していない)。 */
        boolean free(int x, int y, int z) {
            if (!loaded(x, y, z)) return false;
            long k = BlockPos.asLong(x, y, z);
            if (planned.contains(k) || reserved.contains(k)) return false;
            BlockState s = state(x, y, z);
            if (!(s.isAir() || s.canBeReplaced()) || !s.getFluidState().isEmpty()) return false;
            return allowInterior || !interior(x, y, z);
        }

        boolean structAdj(int x, int y, int z) {
            for (Direction d : Direction.values()) {
                if (struct(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ())) return true;
            }
            return false;
        }

        boolean solidAdj(int x, int y, int z) {
            for (Direction d : Direction.values()) {
                int nx = x + d.getStepX(), ny = y + d.getStepY(), nz = z + d.getStepZ();
                if (struct(nx, ny, nz)) return true;
                if (loaded(nx, ny, nz) && state(nx, ny, nz).blocksMotion()) return true;
            }
            return false;
        }

        boolean nearDoor(int x, int z, int r) {
            for (BlockPos d : doors) {
                double dx = d.getX() - x, dz = d.getZ() - z;
                if (dx * dx + dz * dz < (double) r * r) return true;
            }
            return false;
        }

        /** 入口: 扉と、外から 2 マスの高さで歩いて入れる壁の穴。前後 doorClear・横 ±1・高さ 3 を予約する。 */
        void reserveEntrances() {
            for (StructureFusion.Hit h : hits) {
                BoundingBox b = h.piece();
                if ((long) b.getXSpan() * b.getYSpan() * b.getZSpan() > 200_000L) continue; // 大きすぎる部品はなめない(まずない)
                for (int x = b.minX(); x <= b.maxX(); x++) {
                    for (int z = b.minZ(); z <= b.maxZ(); z++) {
                        for (int y = b.minY(); y <= b.maxY(); y++) {
                            if (!loaded(x, y, z)) continue;
                            BlockState s = state(x, y, z);
                            if (s.is(BlockTags.DOORS) && s.hasProperty(DoorBlock.HALF) && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                                reserve(x, y, z, s.getValue(DoorBlock.FACING));
                                continue;
                            }
                            boolean edgeX = x == b.minX() || x == b.maxX(), edgeZ = z == b.minZ() || z == b.maxZ();
                            if (!edgeX && !edgeZ) continue;
                            if (!s.isAir() || !loaded(x, y + 1, z) || !state(x, y + 1, z).isAir()) continue;
                            if (!loaded(x, y - 1, z) || !state(x, y - 1, z).blocksMotion()) continue;
                            if (edgeX && struct(x, y, z - 1) && struct(x, y, z + 1)) {
                                reserve(x, y, z, x == b.minX() ? Direction.WEST : Direction.EAST);
                            } else if (edgeZ && struct(x - 1, y, z) && struct(x + 1, y, z)) {
                                reserve(x, y, z, z == b.minZ() ? Direction.NORTH : Direction.SOUTH);
                            }
                        }
                    }
                }
            }
        }

        void reserve(int x, int y, int z, Direction out) {
            doors.add(new BlockPos(x, y, z));
            Direction side = out.getClockWise();
            int n = t.doorClear;
            for (int k = -n; k <= n; k++) {
                for (int l = -1; l <= 1; l++) {
                    for (int h = 0; h <= 2; h++) {
                        reserved.add(BlockPos.asLong(x + out.getStepX() * k + side.getStepX() * l, y + h, z + out.getStepZ() * k + side.getStepZ() * l));
                    }
                }
            }
        }
    }

    /** 置く物 1 つ(世界の座標)。 */
    private record Cell(int x, int y, int z, int mat, int faces, double key) {
    }

    /** 構造物のそばの小さい版の形を作る。根元が見つからなければ null。 */
    @Nullable
    static long[] plan(ServerLevel level, ForestOrgans.Organ o, List<StructureFusion.Hit> hits) {
        return plan(level, o, hits, StructureFusion.table());
    }

    /** 値を渡して作る(テストで根元の種類を決めるため)。 */
    @Nullable
    static long[] plan(ServerLevel level, ForestOrgans.Organ o, List<StructureFusion.Hit> hits, FusionTable t) {
        Random rnd = new Random(o.seed * 0x9E3779B97F4A7C15L + 0xF0510L);
        Ctx c = new Ctx(level, hits, t);
        c.reserveEntrances();
        // 根元を付ける部品: 器官の場所にいちばん近い部品
        StructureFusion.Hit near = null;
        double best = Double.MAX_VALUE;
        for (StructureFusion.Hit h : hits) {
            BoundingBox b = h.piece();
            double dx = Math.max(0, Math.max(b.minX() - o.x, o.x - b.maxX())), dz = Math.max(0, Math.max(b.minZ() - o.z, o.z - b.maxZ()));
            if (dx * dx + dz * dz < best && c.building(b)) {
                best = dx * dx + dz * dz;
                near = h;
            }
        }
        if (near == null) return NO_BUILDING; // 道・畑だけ: ふつうの器官に(置く所で守る)
        BoundingBox pb = near.piece();
        double[] center = {(pb.minX() + pb.maxX() + 1) / 2.0D, (pb.minZ() + pb.maxZ() + 1) / 2.0D};
        double[] w = t.anchorWeights[near.pieceClass().ordinal()].clone();
        List<int[]> walls = wallCandidates(c, pb), roofs = roofCandidates(c, pb), grounds = groundCandidates(c, pb);
        List<List<int[]>> lists = List.of(walls, roofs, grounds);
        double total = 0.0D;
        for (int i = 0; i < 3; i++) {
            if (lists.get(i).isEmpty()) w[i] = 0.0D;
            total += w[i];
        }
        if (total <= 0.0D) {
            // 重みの付いた根元が無ければ、ある物で(地面 → 壁 → 屋根)
            int pick = !grounds.isEmpty() ? 2 : !walls.isEmpty() ? 0 : !roofs.isEmpty() ? 1 : -1;
            if (pick < 0) return null;
            w = new double[3];
            w[pick] = 1.0D;
            total = 1.0D;
        }
        double r = rnd.nextDouble() * total;
        int type = 2;
        for (int i = 0; i < 3; i++) {
            r -= w[i];
            if (r <= 0.0D && w[i] > 0.0D) {
                type = i;
                break;
            }
        }
        List<int[]> cands = lists.get(type);
        int[] a = type == 1 ? cands.get(rnd.nextInt(Math.min(cands.size(), 40))) : cands.get(rnd.nextInt(cands.size()));
        double tr = Math.toRadians(t.tilt);
        double[] normal, axis;
        if (type == 0) {
            normal = new double[]{a[3], 0, a[4]};
            axis = new double[]{a[3] * Math.cos(tr), Math.sin(tr), a[4] * Math.cos(tr)};
        } else if (type == 1) {
            normal = new double[]{0, 1, 0};
            double h = rnd.nextDouble() * Math.PI * 2.0D;
            axis = new double[]{Math.cos(h) * Math.cos(tr), Math.sin(tr), Math.sin(h) * Math.cos(tr)};
        } else {
            normal = new double[]{0, 1, 0};
            double ax = a[0] + 0.5D - center[0], az = a[2] + 0.5D - center[1];
            double l = Math.max(1.0E-3D, Math.hypot(ax, az));
            axis = new double[]{-ax / l * Math.cos(tr), Math.sin(tr), -az / l * Math.cos(tr)};
        }
        double al = Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
        for (int i = 0; i < 3; i++) axis[i] /= al;

        List<Cell> cells = new ArrayList<>();
        // --- 形: 柄・袋・芯(試作の growOrgan)
        double[] p0 = {a[0] + 0.5D + normal[0] * 0.5D, a[1] + 0.5D + normal[1] * 0.5D, a[2] + 0.5D + normal[2] * 0.5D};
        double ls = t.stalkLength, sa = t.sacLength, sr = t.sacRadius;
        double cAlong = ls + sa - 0.8D;
        double[] core = {p0[0] + axis[0] * cAlong, p0[1] + axis[1] * cAlong, p0[2] + axis[2] * cAlong};
        int rr = (int) Math.ceil(ls + 2 * sa + sr + 1);
        List<int[]> sacCells = new ArrayList<>();
        boolean ley = o.kind == OrganKind.LEY;
        for (int x = (int) Math.floor(p0[0] - rr); x <= p0[0] + rr; x++) {
            for (int y = (int) Math.floor(p0[1] - rr); y <= p0[1] + rr; y++) {
                for (int z = (int) Math.floor(p0[2] - rr); z <= p0[2] + rr; z++) {
                    double qx = x + 0.5D - p0[0], qy = y + 0.5D - p0[1], qz = z + 0.5D - p0[2];
                    double s = qx * axis[0] + qy * axis[1] + qz * axis[2];
                    double rad = Math.sqrt(sq(qx - axis[0] * s) + sq(qy - axis[1] * s) + sq(qz - axis[2] * s));
                    double dc = Math.sqrt(sq(x + 0.5D - core[0]) + sq(y + 0.5D - core[1]) + sq(z + 0.5D - core[2]));
                    double sc = s - cAlong;
                    double bump = 0.18D * Math.sin(x * 1.7D + z * 1.3D) * Math.cos(y * 1.9D);
                    boolean inSac = sq(sc / sa) + sq(rad / sr) <= 1.0D + bump;
                    int mat;
                    if (dc <= t.coreRadius) {
                        mat = OrganBlueprint.M_CORE;
                    } else if (inSac) {
                        boolean shell = sq(sc / (sa - 1.1D)) + sq(rad / (sr - 1.1D)) > 1.0D;
                        if (!shell || Math.floorMod(x * 7 + y * 13 + z * 5, 11) == 0) continue;
                        mat = ley && Math.floorMod(x * 5 + y * 3 + z * 11, 9) == 0 ? OrganBlueprint.M_CRYSTAL : OrganBlueprint.M_SAC;
                    } else if (s >= -0.6D && s <= ls + 1.0D && rad <= 2.1D - Math.max(0.0D, s) / ls * 0.9D) {
                        mat = OrganBlueprint.M_LOG;
                    } else {
                        continue;
                    }
                    if (!c.free(x, y, z)) continue; // 建物のマス・入口・部屋の中は切れる
                    c.planned.add(BlockPos.asLong(x, y, z));
                    cells.add(new Cell(x, y, z, mat, 0, s));
                    if (mat == OrganBlueprint.M_SAC) sacCells.add(new int[]{x, y, z});
                }
            }
        }
        if (cells.stream().noneMatch(e -> e.mat == OrganBlueprint.M_CORE)) return null; // 芯の置き場が無い = 器官にならない
        // --- 脈: 根元のまわりを這う光る筋と覆い
        double key = 100.0D;
        int[] s0 = {(int) Math.floor(p0[0]), (int) Math.floor(p0[1]), (int) Math.floor(p0[2])};
        for (int k = 0; k < t.veinCrawls; k++) {
            Crawl cr = new Crawl();
            cr.steps = t.veinStepsMin + rnd.nextInt(t.veinStepsMax - t.veinStepsMin + 1);
            cr.sign = k % 2 == 0 ? -1 : 1;
            cr.upW = (rnd.nextDouble() - 0.5D) * 1.6D;
            cr.wrapW = 0.6D;
            cr.noise = 1.2D;
            cr.center = center;
            cr.vein = true;
            cr.ground = true; // 地面の根元からは地面を這って壁へ(壁・屋根の根元なら壁に沿う)
            cr.toward = new int[]{(int) center[0], pb.minY() + 1, (int) center[1]};
            cr.towardW = 0.8D;
            key = crawl(c, s0[0], s0[1], s0[2], cr, rnd, cells, key);
        }
        // --- 蔦の触手: 袋から一番近い壁へ渡り、壁に沿って巻きつく
        for (int k = 0; k < t.vines && !sacCells.isEmpty(); k++) {
            int[] st = sacCells.get(rnd.nextInt(sacCells.size()));
            int[] target = nearestStruct(c, st, t.vineReach);
            if (target == null) continue;
            Crawl bridge = new Crawl();
            bridge.steps = 14;
            bridge.sign = 1;
            bridge.upW = -0.1D;
            bridge.noise = 0.0D;
            bridge.toward = target;
            bridge.towardW = 2.6D;
            bridge.free = true;
            bridge.center = center;
            bridge.mat = OrganBlueprint.M_VINE;
            bridge.stopAtWall = true;
            key = crawl(c, st[0], st[1], st[2], bridge, rnd, cells, key);
            int[] wallStart = nearestHug(c, target);
            if (wallStart == null) continue;
            boolean down = k % 3 == 2;
            Crawl hug = new Crawl();
            hug.steps = t.vineStepsMin + rnd.nextInt(t.vineStepsMax - t.vineStepsMin + 1);
            hug.sign = k % 2 == 0 ? -1 : 1;
            hug.upW = down ? -0.6D : 0.35D + rnd.nextDouble() * 0.3D;
            hug.wrapW = 1.2D;
            hug.noise = 0.7D;
            hug.ground = down;
            hug.center = center;
            hug.mat = OrganBlueprint.M_VINE;
            hug.cover = true;
            if (c.free(wallStart[0], wallStart[1], wallStart[2])) {
                c.planned.add(BlockPos.asLong(wallStart[0], wallStart[1], wallStart[2]));
                cells.add(new Cell(wallStart[0], wallStart[1], wallStart[2], OrganBlueprint.M_VINE, 0, key += 0.01D));
                key = covers(c, wallStart[0], wallStart[1], wallStart[2], cells, key);
            }
            key = crawl(c, wallStart[0], wallStart[1], wallStart[2], hug, rnd, cells, key);
        }
        // --- 列にする(錨からのずれ。8 ビットに入らない所は捨てる)
        BlockPos anchor = o.anchor();
        cells.sort((p, q) -> Double.compare(p.key, q.key));
        LongArrayList out = new LongArrayList();
        LongOpenHashSet coverSeen = new LongOpenHashSet();
        for (Cell e : cells) {
            int dx = e.x - anchor.getX(), dy = e.y - anchor.getY(), dz = e.z - anchor.getZ();
            if (Math.abs(dx) > 120 || Math.abs(dy) > 120 || Math.abs(dz) > 120) continue;
            if (e.mat == OrganBlueprint.M_COVER && !coverSeen.add(BlockPos.asLong(e.x, e.y, e.z))) continue;
            out.add(OrganBlueprint.pack(dx, dy, dz, e.mat, e.faces));
        }
        return out.toLongArray();
    }

    private static double sq(double v) {
        return v * v;
    }

    /** 壁の側面の根元: 建物のブロックで、横の 1 つの向きに 3 マス空いている所(部品の高さの 3〜8 割・入口から離す)。{x,y,z,nx,nz}。 */
    static List<int[]> wallCandidates(Ctx c, BoundingBox b) {
        List<int[]> out = new ArrayList<>();
        int y0 = b.minY() + Math.max(1, (int) Math.round(b.getYSpan() * 0.3D)), y1 = b.minY() + Math.max(1, (int) Math.round(b.getYSpan() * 0.8D));
        for (int y = y0; y <= y1; y++) {
            for (int x = b.minX(); x <= b.maxX(); x++) {
                for (int z = b.minZ(); z <= b.maxZ(); z++) {
                    if (!c.struct(x, y, z)) continue;
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        boolean ok = true;
                        for (int k = 1; k <= 3 && ok; k++) ok = c.free(x + d.getStepX() * k, y, z + d.getStepZ() * k);
                        if (ok && !c.nearDoor(x, z, c.t.doorClear)) out.add(new int[]{x, y, z, d.getStepX(), d.getStepZ()});
                    }
                }
            }
        }
        return out;
    }

    /** 屋根の上の根元: 部品の列のいちばん上の建物のブロック(高さの 45% より上)で、上が空いている所。高い順。 */
    static List<int[]> roofCandidates(Ctx c, BoundingBox b) {
        List<int[]> out = new ArrayList<>();
        int minY = b.minY() + (int) Math.round(b.getYSpan() * 0.45D);
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                for (int y = b.maxY() + 1; y >= b.minY(); y--) {
                    if (!c.loaded(x, y, z)) break;
                    BlockState s = c.state(x, y, z);
                    if (s.isAir() || s.canBeReplaced()) continue;
                    if (c.struct(x, y, z) && y >= minY && c.free(x, y + 1, z) && c.free(x, y + 2, z)) out.add(new int[]{x, y, z});
                    break;
                }
            }
        }
        out.sort((p, q) -> Integer.compare(q[1], p[1]));
        return out;
    }

    /** そばの地面の根元: 部品の箱から横に 2〜3 マス外の地面(入口から離す)。 */
    static List<int[]> groundCandidates(Ctx c, BoundingBox b) {
        List<int[]> out = new ArrayList<>();
        for (int x = b.minX() - 4; x <= b.maxX() + 4; x++) {
            for (int z = b.minZ() - 4; z <= b.maxZ() + 4; z++) {
                int dx = Math.max(0, Math.max(b.minX() - x, x - b.maxX())), dz = Math.max(0, Math.max(b.minZ() - z, z - b.maxZ()));
                int d = Math.max(dx, dz);
                if (d < 2 || d > 3) continue;
                if (!c.loaded(x, 64, z) || c.nearDoor(x, z, c.t.doorClear + 1)) continue;
                int gy = OrganClearing.groundY(c.level, x, z);
                if (c.inPiece(x, gy, z)) continue;
                if (c.free(x, gy + 1, z) && c.free(x, gy + 2, z)) out.add(new int[]{x, gy, z});
            }
        }
        return out;
    }

    @Nullable
    static int[] nearestStruct(Ctx c, int[] from, int reach) {
        int[] best = null;
        int bd = Integer.MAX_VALUE;
        for (int a = -reach; a <= reach; a++) {
            for (int b = -reach; b <= 4; b++) {
                for (int d = -reach; d <= reach; d++) {
                    int dd = a * a + b * b + d * d;
                    if (dd >= bd) continue;
                    if (c.struct(from[0] + a, from[1] + b, from[2] + d)) {
                        bd = dd;
                        best = new int[]{from[0] + a, from[1] + b, from[2] + d};
                    }
                }
            }
        }
        return best;
    }

    @Nullable
    static int[] nearestHug(Ctx c, int[] target) {
        int[] best = null;
        int bd = Integer.MAX_VALUE;
        for (int a = -2; a <= 2; a++) {
            for (int b = -2; b <= 2; b++) {
                for (int d = -2; d <= 2; d++) {
                    int x = target[0] + a, y = target[1] + b, z = target[2] + d;
                    int dd = a * a + b * b + d * d;
                    if (dd >= bd || !c.free(x, y, z) || !c.structAdj(x, y, z)) continue;
                    bd = dd;
                    best = new int[]{x, y, z};
                }
            }
        }
        return best;
    }

    /** 這い方(試作の crawl の引数)。 */
    static final class Crawl {
        int steps;
        int sign = 1;
        double upW, wrapW, noise, towardW;
        @Nullable
        int[] toward;
        boolean free, ground, cover, vein, stopAtWall;
        int mat;
        double[] center;
    }

    /** そのマスに触れる建物のマスを覆いの印にする。 */
    private static double covers(Ctx c, int x, int y, int z, List<Cell> cells, double key) {
        for (Direction d : Direction.values()) {
            int nx = x + d.getStepX(), ny = y + d.getStepY(), nz = z + d.getStepZ();
            if (c.struct(nx, ny, nz)) cells.add(new Cell(nx, ny, nz, OrganBlueprint.M_COVER, 0, key += 0.001D));
        }
        return key;
    }

    /**
     * 壁に沿って這う(試作の crawl)。26 方向の隣のうち、空いていて(壁に触れているか、free・ground の条件を満たす)、
     * 中心のまわりを回る向き・上への寄り・前の向き・ねらい・ゆらぎの点がいちばん高いマスへ進む。置く物と覆いを足し、最後の順の鍵を返す。
     */
    static double crawl(Ctx c, int sx, int sy, int sz, Crawl o, Random rnd, List<Cell> cells, double key) {
        int x = sx, y = sy, z = sz;
        double px = 0, py = 0, pz = 0;
        LongOpenHashSet seen = new LongOpenHashSet();
        for (int s = 0; s < o.steps; s++) {
            int[] best = null;
            double bs = -1.0E9D, bdx = 0, bdy = 0, bdz = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        int nx = x + dx, ny = y + dy, nz = z + dz;
                        long k = BlockPos.asLong(nx, ny, nz);
                        if (seen.contains(k) || !c.free(nx, ny, nz)) continue;
                        boolean hug = c.structAdj(nx, ny, nz);
                        if (!hug && !o.free && !(o.ground && c.solidAdj(nx, ny, nz))) continue;
                        double rx = nx - o.center[0], rz = nz - o.center[1], rl = Math.max(1.0E-3D, Math.hypot(rx, rz));
                        double tx = -rz / rl * o.sign, tz = rx / rl * o.sign;
                        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        double sc = (dx * tx + dz * tz) / len * o.wrapW + dy / len * o.upW + (dx * px + dy * py + dz * pz) / len * 0.7D
                                + (rnd.nextDouble() - 0.5D) * o.noise;
                        if (hug) sc += 0.9D;
                        if (o.toward != null) {
                            double qx = o.toward[0] - x, qy = o.toward[1] - y, qz = o.toward[2] - z;
                            double ql = Math.max(1.0E-3D, Math.sqrt(qx * qx + qy * qy + qz * qz));
                            sc += (qx * dx + qy * dy + qz * dz) / ql / len * o.towardW;
                        }
                        if (sc > bs) {
                            bs = sc;
                            best = new int[]{nx, ny, nz};
                            bdx = dx / len;
                            bdy = dy / len;
                            bdz = dz / len;
                        }
                    }
                }
            }
            if (best == null) break;
            x = best[0];
            y = best[1];
            z = best[2];
            px = bdx;
            py = bdy;
            pz = bdz;
            seen.add(BlockPos.asLong(x, y, z));
            boolean touches = c.structAdj(x, y, z);
            if (o.vein) {
                // 脈: 壁に触れる空気のマスに光る筋(触れる面)を半分ほど、覆いは全部
                if (touches) {
                    if (rnd.nextDouble() < 0.5D) {
                        int faces = 0;
                        for (Direction d : Direction.values()) {
                            if (c.struct(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ())) faces |= 1 << d.get3DDataValue();
                        }
                        c.planned.add(BlockPos.asLong(x, y, z));
                        cells.add(new Cell(x, y, z, OrganBlueprint.M_VEIN, faces, key += 0.01D));
                    }
                    key = covers(c, x, y, z, cells, key);
                }
            } else if (o.mat != 0) {
                c.planned.add(BlockPos.asLong(x, y, z));
                cells.add(new Cell(x, y, z, o.mat, 0, key += 0.01D));
                if (o.cover && touches) key = covers(c, x, y, z, cells, key);
            }
            if (o.stopAtWall && touches) break;
        }
        return key;
    }
}
