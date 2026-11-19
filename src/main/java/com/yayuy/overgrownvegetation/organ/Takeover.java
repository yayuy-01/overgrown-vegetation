package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.pushback.RingWards;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.worldgen.fusion.FusionTable;
import com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 乗っ取り(2026-11-19 夜 構造物と森の融合 段 3。ユーザー「器官が覆いかぶさっているだけではなく、決まりを持たず、いろんな場所から生えていて、
 * 植物に乗っ取られている感じ」「構造物専用の動かし方はうまくいかない気がする」。試作 structure-fusion-proto.html 第 10 版の growTakeover・organicOrgan)。
 *
 * <ul>
 *   <li><b>人工のブロック</b>(二段構え): 構造物の記録の中(地表の部品の箱)では、自然のブロック({@link #NATURAL})以外の固いブロック。
 *       記録の外では、建材({@link #BUILDING})だけ。プレイヤーの建物も対象。この MOD の木・葉・器官は数えない(器官から器官が生えないように)。</li>
 *   <li><b>広がり</b>: 過繁茂のチャンクで、プレイヤーのまわりを少しずつ見て、人工のブロックの露出面から器官を 1 つずつ芽吹かせる。
 *       露出面 {@code facesPerOrgan} 面につき 1 つまで(面の広さに比べる・上限なし)。器官どうしは間を空ける。押し返す道具の効く所では芽吹かない。
 *       繁茂の段階の蔦・苔・木と草は、ふつうの汚染(建物の侵食 {@code BuildCorrosion} を含む)に任せる。</li>
 *   <li><b>器官の形</b>({@link #planOrganic}): 面から斜め・上(天井なら下)へうねって節のある茎、先のいびつな嚢、半ば出た芯、脇芽、根元から壁を這う蔦。
 *       大きい器官と、ときどき小さい器官は根元を突き破る(人工のブロックだけ。戸は上下とも)。部屋の中・入口も塞いでよい。</li>
 *   <li>器官はふつうの森の器官(種類はふつうの 5 種類からランダム、働きもそのとおり)。芯を壊すと枯れ、伸びた物は剥がれる。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class Takeover {
    private Takeover() {
    }

    /** 自然のブロック(記録の中で人工と数えない物)。データパックで足し引きできる。 */
    public static final TagKey<Block> NATURAL = TagKey.create(Registries.BLOCK, OvergrownVegetation.id("takeover_natural"));
    /** 建材(記録の外で人工と数える物)。データパックで足し引きできる。 */
    public static final TagKey<Block> BUILDING = TagKey.create(Registries.BLOCK, OvergrownVegetation.id("takeover_building"));

    // ---------------------------------------------------------------- 人工のブロック

    /** この MOD の木・葉・植物・器官か(人工に数えない)。建材の汚染した形(過繁茂の板など)は建材のタグで人工に入る。 */
    static boolean forestPart(BlockState s) {
        if (s.is(BUILDING)) return false;
        Block b = s.getBlock();
        if (b instanceof OrganTissueBlock || b instanceof OrganCoreBlock || b instanceof OrganVeinBlock) return true;
        if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES)) return true;
        return OvergrownVegetation.MODID.equals(BuiltInRegistries.BLOCK.getKey(b).getNamespace()) && !s.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    /** 状態だけで見た、人工になりうるか(チャンクの区画を素早く見るため)。 */
    static boolean maybeArtificial(BlockState s) {
        if (s.isAir() || s.canBeReplaced() || !s.getFluidState().isEmpty()) return false;
        if (s.is(BUILDING)) return true;
        return !s.is(NATURAL) && s.blocksMotion() && !forestPart(s);
    }

    /**
     * 人工のブロックか(二段構え)。記録の中(守る範囲 = 地表の部品の箱 + 余白)なら自然のブロック以外の固いブロック、記録の外・まだ分からない所は建材だけ。
     */
    public static boolean isArtificial(ServerLevel level, BlockPos pos, BlockState s) {
        if (!maybeArtificial(s)) return false;
        if (s.is(BUILDING)) return true;
        return StructureFusion.state(level, pos) == StructureFusion.PROTECTED;
    }

    /** 露出面 [x, y, z, nx, ny, nz](1 マスに 1 つ、ランダムな向き)。 */
    public record Face(int x, int y, int z, int nx, int ny, int nz) {
        BlockPos pos() {
            return new BlockPos(x, y, z);
        }

        BlockPos out() {
            return new BlockPos(x + nx, y + ny, z + nz);
        }
    }

    /** チャンクの人工のブロックの露出面を集める(区画は maybeHas で飛ばす)。多いときは max 個まで間引いて返す。数は {@code counted[0]} に。 */
    public static List<Face> faces(ServerLevel level, LevelChunk ch, Random rnd, int max, int[] counted) {
        List<Face> out = new ArrayList<>();
        int seen = 0;
        LevelChunkSection[] sections = ch.getSections();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(), q = new BlockPos.MutableBlockPos();
        int bx = ch.getPos().getMinBlockX(), bz = ch.getPos().getMinBlockZ();
        Direction[] dirs = Direction.values();
        for (int si = 0; si < sections.length; si++) {
            LevelChunkSection sec = sections[si];
            if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(Takeover::maybeArtificial)) continue;
            int by = level.getSectionYFromSectionIndex(si) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState s = sec.getBlockState(x, y, z);
                        if (!maybeArtificial(s)) continue;
                        p.set(bx + x, by + y, bz + z);
                        if (!isArtificial(level, p, s)) continue;
                        int start = rnd.nextInt(6);
                        for (int k = 0; k < 6; k++) {
                            Direction d = dirs[(start + k) % 6];
                            q.setWithOffset(p, d);
                            if (level.getChunkSource().getChunkNow(q.getX() >> 4, q.getZ() >> 4) == null) continue;
                            BlockState n = level.getBlockState(q);
                            if (!(n.isAir() || n.canBeReplaced()) || !n.getFluidState().isEmpty()) continue;
                            seen++;
                            Face f = new Face(p.getX(), p.getY(), p.getZ(), d.getStepX(), d.getStepY(), d.getStepZ());
                            if (out.size() < max) out.add(f);
                            else {
                                int r = rnd.nextInt(seen);
                                if (r < max) out.set(r, f); // 間引き(どの面も同じ確率で残る)
                            }
                            break;
                        }
                    }
                }
            }
        }
        counted[0] = seen;
        return out;
    }

    // ---------------------------------------------------------------- 広がり

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return; // 森の器官は地上だけ
        FusionTable t = StructureFusion.table();
        if (!t.takeoverEnabled || level.players().isEmpty() || level.getGameTime() % t.takeoverScanTicks != 0) return;
        if (!TickBudget.mayStart(PerfJob.ORGAN_BUILD)) return;
        long start = TickBudget.begin();
        int visited = 0;
        Random rnd = new Random(level.getGameTime() * 0x9E3779B97F4A7C15L ^ level.getSeed());
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer p : level.players()) if (!p.isSpectator()) players.add(p);
        for (int i = 0; i < t.takeoverChunksPerScan && !players.isEmpty() && (i == 0 || TickBudget.timeLeft()); i++) { // 1 回が重い(隙間の草で最悪 30 ms)ので予算が尽きたら次の回へ
            ServerPlayer pl = players.get(rnd.nextInt(players.size()));
            ChunkPos c = pl.chunkPosition();
            int r = t.takeoverRadiusChunks;
            visit(level, c.x + rnd.nextInt(2 * r + 1) - r, c.z + rnd.nextInt(2 * r + 1) - r, rnd, t, false);
            visited++;
        }
        TickBudget.end(PerfJob.ORGAN_BUILD, start, visited);
    }

    /** 見た結果(テスト・報告用)。 */
    public record Visit(int faces, int organs, @Nullable ForestOrgans.Organ planted, String why) {
    }

    /**
     * チャンクを 1 回見る。過繁茂(force なら段階を見ない)で、まだ器官が露出面に比べて少なければ、確率で器官を 1 つ芽吹かせる。
     */
    public static Visit visit(ServerLevel level, int cx, int cz, Random rnd, FusionTable t, boolean force) {
        LevelChunk ch = level.getChunkSource().getChunkNow(cx, cz);
        if (ch == null) return new Visit(0, 0, null, "読み込まれていない");
        net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome = level.getBiome(new BlockPos((cx << 4) + 8, level.getSeaLevel(), (cz << 4) + 8));
        if (!force && !ModBiomes.isOvergrownLike(biome)) return new Visit(0, 0, null, "繁茂でない");
        lastGaps = greenGaps(level, ch, rnd, t); // 建物どうしの間・隙間の草と蔦(繁茂・過繁茂とも)
        if (!force && !ModBiomes.isFullyOvergrown(biome)) return new Visit(0, 0, null, "繁茂(器官は過繁茂だけ)");
        if (!force && rnd.nextDouble() >= t.takeoverOrganChancePerVisit) return new Visit(0, 0, null, "確率で見送り");
        int[] counted = new int[1];
        List<Face> fs = faces(level, ch, rnd, 512, counted);
        if (fs.isEmpty()) return new Visit(0, 0, null, "人工の露出面が無い");
        ForestOrgans data = ForestOrgans.get(level);
        int here = 0;
        for (ForestOrgans.Organ o : data.organs()) {
            if (o.takeover != null && (o.takeover[0] >> 4) == cx && (o.takeover[2] >> 4) == cz) here++;
        }
        int allowed = Math.max(1, counted[0] / t.takeoverFacesPerOrgan);
        if (here >= allowed) return new Visit(counted[0], here, null, "露出面に比べて足りている");
        boolean big = rnd.nextDouble() < t.takeoverBigChance;
        int spacing = big ? t.takeoverSpacingBig : t.takeoverSpacingSmall;
        for (int tries = 0; tries < 24; tries++) {
            Face f = fs.get(rnd.nextInt(fs.size()));
            if (big && f.ny() == -1) continue; // 大きい器官は天井から下げない
            if (RingWards.guarded(level, f.out())) continue; // 押し返す道具の効く所
            if (!farFromOthers(data, f, spacing)) continue;
            ForestOrgans.Organ o = plant(level, data, f, big, rnd);
            return new Visit(counted[0], here + 1, o, big ? "大きい器官" : "小さい器官");
        }
        return new Visit(counted[0], here, null, "間を空けられる面が無い");
    }

    /** 直前の見回りで草と蔦を生やした隙間の数(コマンド・テスト用)。 */
    static int lastGaps;

    /**
     * 建物どうしの間・路地・壁と地面の隅(段 3 追記 11-20 ユーザー「建物同士の間や隙間には草の蔦が生えて」)。チャンクの列を選び、地面の上の空いたマスが
     * 東西南北のうち 2 方向以上で gapReach 以内の人工のブロックに挟まれているか、すぐ隣が人工の壁なら隙間とみなす。地面に下草を、隣の壁に蔦を
     * 確率 gapPlantChance で生やす(ふつうの汚染の植物の生やし方 {@link com.yayuy.overgrownvegetation.corruption.CorruptionSpreader#growSurfacePlants})。
     * 屋根の上(足元が人工のブロック)は数えない。押し返す道具の効く所には生やさない。生やした隙間の数を返す。
     */
    public static int greenGaps(ServerLevel level, LevelChunk ch, Random rnd, FusionTable t) {
        int done = 0, bx = ch.getPos().getMinBlockX(), bz = ch.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int i = 0; i < t.takeoverGapCellsPerVisit * 4 && done < t.takeoverGapCellsPerVisit; i++) {
            int x = bx + rnd.nextInt(16), z = bz + rnd.nextInt(16);
            int y = ch.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
            BlockPos cell = new BlockPos(x, y, z), ground = cell.below();
            BlockState c = level.getBlockState(cell), g = level.getBlockState(ground);
            if (!(c.isAir() || c.canBeReplaced()) || !c.getFluidState().isEmpty() || isArtificial(level, ground, g)) continue;
            int sides = 0;
            List<BlockPos> walls = new ArrayList<>();
            for (int[] d : dirs) {
                for (int k = 1; k <= t.takeoverGapReach; k++) {
                    boolean hit = false;
                    for (int dy = 0; dy <= 1 && !hit; dy++) {
                        m.set(x + d[0] * k, y + dy, z + d[1] * k);
                        if (level.getChunkSource().getChunkNow(m.getX() >> 4, m.getZ() >> 4) == null) continue;
                        BlockState s = level.getBlockState(m);
                        if (isArtificial(level, m, s)) {
                            hit = true;
                            if (k == 1) walls.add(m.immutable());
                        }
                    }
                    if (hit) {
                        sides++;
                        break;
                    }
                }
            }
            if (sides < 2 && walls.isEmpty()) continue;
            if (RingWards.guarded(level, cell)) continue;
            com.yayuy.overgrownvegetation.corruption.CorruptionSpreader.growSurfacePlants(level, ground, g, t.takeoverGapPlantChance);
            for (BlockPos w : walls) com.yayuy.overgrownvegetation.corruption.CorruptionSpreader.growSurfacePlants(level, w, level.getBlockState(w), t.takeoverGapPlantChance);
            done++;
        }
        return done;
    }

    private static boolean farFromOthers(ForestOrgans data, Face f, int spacing) {
        for (ForestOrgans.Organ o : data.organs()) {
            if (o.takeover == null) continue;
            int r = o.small ? spacing : Math.max(spacing, StructureFusion.table().takeoverSpacingBig);
            double dx = o.takeover[0] - f.x(), dy = o.takeover[1] - f.y(), dz = o.takeover[2] - f.z();
            if (dx * dx + dy * dy + dz * dz < (double) r * r) return false;
        }
        return true;
    }

    /** 面に器官を植える(種類はふつうの 5 種類からランダム)。形は器官の側で作る({@link OrganFusion#decide} → {@link #planOrganic})。 */
    public static ForestOrgans.Organ plant(ServerLevel level, ForestOrgans data, Face f, boolean big, Random rnd) {
        OrganKind kind = OrganKind.values()[rnd.nextInt(OrganKind.values().length)];
        ForestOrgans.Organ o = data.plant(level, kind, !big, 0, rnd.nextInt(), 0, f.x(), f.z());
        o.y = f.y();
        o.takeover = new int[]{f.x(), f.y(), f.z(), f.nx(), f.ny(), f.nz()};
        o.cleared = true; // 建物から生えるので、まわりの木は払わない
        data.changed();
        return o;
    }

    // ---------------------------------------------------------------- 器官の形(試作の organicOrgan)

    private static double[] nrm(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return l < 1.0E-9D ? new double[]{0, 1, 0} : new double[]{x / l, y / l, z / l};
    }

    /** 置く物の入れ物(先に入った物が勝つ。芯は嚢・茎の上に入れる)。 */
    private static final class Body {
        final Map<Long, Integer> cells = new LinkedHashMap<>();

        void put(int x, int y, int z, int m) {
            long k = BlockPos.asLong(x, y, z);
            Integer cur = cells.get(k);
            if (cur == null || (m == OrganBlueprint.M_CORE && cur != OrganBlueprint.M_CARVE)) cells.put(k, m);
        }

        void ball(double cx, double cy, double cz, double r, int m) {
            int big = (int) Math.ceil(r);
            for (int a = -big; a <= big; a++) {
                for (int b = -big; b <= big; b++) {
                    for (int c = -big; c <= big; c++) {
                        int x = (int) Math.floor(cx) + a, y = (int) Math.floor(cy) + b, z = (int) Math.floor(cz) + c;
                        double dx = x + 0.5D - cx, dy = y + 0.5D - cy, dz = z + 0.5D - cz;
                        if (dx * dx + dy * dy + dz * dz <= r * r) put(x, y, z, m);
                    }
                }
            }
        }
    }

    /**
     * 乗っ取りの器官の形(試作の organicOrgan)。面から斜め・上(天井なら下)へうねって節のある茎 → 先のいびつな嚢 → 先に半ば出た芯 → 脇芽 → 根元から壁を這う蔦。
     * 大きい器官(と holeChance の小さい器官)は根元を突き破る(人工のブロックだけ。戸は上下とも)。芯の置けるマスが無ければ null。
     * 置くのは {@link OrganWorld#place} が空気・草花のマスにだけ(穴は人工のブロックだけ)。
     */
    @Nullable
    public static long[] planOrganic(ServerLevel level, ForestOrgans.Organ o, FusionTable t) {
        int[] f = o.takeover;
        if (f == null || f.length < 6) return null;
        boolean big = !o.small;
        Random rnd = new Random(o.seed * 0x9E3779B97F4A7C15L + 0x7A0EL);
        int ax = f[0], ay = f[1], az = f[2], nx = f[3], ny = f[4], nz = f[5];
        double[] d;
        if (ny == 1) d = nrm((rnd.nextDouble() - 0.5D) * 1.4D, 1, (rnd.nextDouble() - 0.5D) * 1.4D);
        else if (ny == -1) d = nrm((rnd.nextDouble() - 0.5D) * 1.6D, -1, (rnd.nextDouble() - 0.5D) * 1.6D);
        else d = nrm(nx + (rnd.nextDouble() - 0.5D) * 0.8D, 0.5D + rnd.nextDouble() * 1.1D, nz + (rnd.nextDouble() - 0.5D) * 0.8D);
        Body body = new Body();
        // 突き破った穴(人工のブロックだけ。置くときにも見直す)
        if (big) {
            for (int k = 0; k < 4; k++) hole(level, body, ax + (int) Math.round((rnd.nextDouble() - 0.5D) * 2), ay + (int) Math.round((rnd.nextDouble() - 0.5D) * 2),
                    az + (int) Math.round((rnd.nextDouble() - 0.5D) * 2));
            hole(level, body, ax, ay, az);
        } else if (rnd.nextDouble() < t.takeoverHoleChance) {
            hole(level, body, ax, ay, az);
        }
        double[] lr = big ? t.takeoverBigLength : t.takeoverSmallLength;
        double len = lr[0] + rnd.nextDouble() * (lr[1] - lr[0]);
        double r0 = big ? 2.2D : 0.95D, r1 = big ? 1.2D : 0.55D;
        double[] p = {ax + 0.5D + nx * 0.6D, ay + 0.5D + ny * 0.6D, az + 0.5D + nz * 0.6D};
        double ph = rnd.nextDouble() * Math.PI * 2.0D, wob = big ? 0.12D : 0.2D, sag = (rnd.nextDouble() - 0.3D) * (big ? 0.025D : 0.05D);
        List<double[]> pts = new ArrayList<>();
        for (double s = 0; s <= len; s += 0.5D) {
            d = nrm(d[0] + Math.cos(ph + s * 0.7D) * wob, d[1] - sag, d[2] + Math.sin(ph + s * 0.9D) * wob);
            p = new double[]{p[0] + d[0] * 0.5D, p[1] + d[1] * 0.5D, p[2] + d[2] * 0.5D};
            double tt = s / len, node = rnd.nextDouble() < 0.1D ? (big ? 0.9D : 0.5D) : 0.0D;
            pts.add(new double[]{p[0], p[1], p[2], r0 + (r1 - r0) * tt + node});
        }
        for (double[] q : pts) body.ball(q[0], q[1], q[2], q[3], OrganBlueprint.M_LOG);
        // 先の嚢(いびつな楕円)
        double ra = big ? 4.5D + rnd.nextDouble() * 2.5D : 1.3D + rnd.nextDouble() * 1.2D, rb = big ? 3.4D + rnd.nextDouble() * 1.6D : 0.9D + rnd.nextDouble() * 0.8D;
        double[] cc = {p[0] + d[0] * ra * 0.75D, p[1] + d[1] * ra * 0.75D, p[2] + d[2] * ra * 0.75D};
        int rr = (int) Math.ceil(ra + 2);
        for (int x = (int) Math.floor(cc[0] - rr); x <= cc[0] + rr; x++) {
            for (int y = (int) Math.floor(cc[1] - rr); y <= cc[1] + rr; y++) {
                for (int z = (int) Math.floor(cc[2] - rr); z <= cc[2] + rr; z++) {
                    double qx = x + 0.5D - cc[0], qy = y + 0.5D - cc[1], qz = z + 0.5D - cc[2], s = qx * d[0] + qy * d[1] + qz * d[2];
                    double rad = Math.sqrt(sq(qx - d[0] * s) + sq(qy - d[1] * s) + sq(qz - d[2] * s));
                    double bump = 0.28D * Math.sin(x * 1.3D + y * 0.7D) * Math.cos(z * 1.1D - y * 0.5D);
                    if (sq(s / ra) + sq(rad / rb) <= 1.0D + bump) body.put(x, y, z, OrganBlueprint.M_SAC);
                }
            }
        }
        // 芯: 嚢の先に半ば出す(外から届く)
        double[] kk = {cc[0] + d[0] * ra * 0.8D, cc[1] + d[1] * ra * 0.8D, cc[2] + d[2] * ra * 0.8D};
        double kr = big ? 1.5D : 0.6D;
        for (int a = -2; a <= 2; a++) {
            for (int b = -2; b <= 2; b++) {
                for (int c = -2; c <= 2; c++) {
                    int x = (int) Math.floor(kk[0]) + a, y = (int) Math.floor(kk[1]) + b, z = (int) Math.floor(kk[2]) + c;
                    if (sq(x + 0.5D - kk[0]) + sq(y + 0.5D - kk[1]) + sq(z + 0.5D - kk[2]) > kr * kr + 0.3D) continue;
                    body.put(x, y, z, OrganBlueprint.M_CORE);
                }
            }
        }
        // 脇芽(小さな莢)
        int nSide = big ? 2 + rnd.nextInt(2) : (rnd.nextDouble() < 0.3D ? 1 : 0);
        for (int k = 0; k < nSide && pts.size() > 4; k++) {
            double[] b0 = pts.get((int) Math.floor(pts.size() * (0.35D + rnd.nextDouble() * 0.45D)));
            double[] sd = nrm(d[0] + (rnd.nextDouble() - 0.5D) * 2, d[1] + (rnd.nextDouble() - 0.3D), d[2] + (rnd.nextDouble() - 0.5D) * 2);
            double[] q = {b0[0], b0[1], b0[2]};
            double sl = big ? 4 + rnd.nextDouble() * 5 : 2 + rnd.nextDouble() * 2;
            for (double s = 0; s <= sl; s += 0.5D) {
                q = new double[]{q[0] + sd[0] * 0.5D, q[1] + sd[1] * 0.5D, q[2] + sd[2] * 0.5D};
                body.ball(q[0], q[1], q[2], big ? 0.9D : 0.5D, OrganBlueprint.M_LOG);
            }
            body.ball(q[0] + sd[0], q[1] + sd[1], q[2] + sd[2], big ? 2.2D : 1.0D, OrganBlueprint.M_SAC);
        }
        // 根元から壁を這う蔦
        int vines = big ? t.takeoverVinesBig : t.takeoverVinesSmall;
        for (int k = 0; k < vines; k++) crawlVine(level, body, ax + nx, ay + ny, az + nz, 12 + rnd.nextInt(big ? 40 : 18), k % 2 == 1 ? 1 : -1,
                (rnd.nextDouble() - 0.5D) * 1.4D, ax, az, rnd);
        // 芯の置けるマスがあるか(建物のブロックに埋もれて壊せない芯は作らない)
        boolean core = false;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (Map.Entry<Long, Integer> e : body.cells.entrySet()) {
            if (e.getValue() != OrganBlueprint.M_CORE) continue;
            BlockState s = level.getBlockState(m.set(e.getKey()));
            if ((s.isAir() || s.canBeReplaced()) && s.getFluidState().isEmpty()) {
                core = true;
                break;
            }
        }
        if (!core) return null;
        // 根元から順に(穴 → 茎 → 嚢・芯 → 蔦)
        LongArrayList out = new LongArrayList();
        for (Map.Entry<Long, Integer> e : body.cells.entrySet()) {
            long k = e.getKey();
            int dx = BlockPos.getX(k) - ax, dy = BlockPos.getY(k) - ay, dz = BlockPos.getZ(k) - az;
            if (Math.abs(dx) > 120 || Math.abs(dy) > 120 || Math.abs(dz) > 120) continue;
            if (RingWards.guarded(level, m.set(k))) continue; // 押し返す道具の効く所には伸ばさない
            out.add(OrganBlueprint.pack(dx, dy, dz, e.getValue(), 0));
        }
        return out.toLongArray();
    }

    private static void hole(ServerLevel level, Body body, int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        BlockState s = level.getBlockState(p);
        if (s.hasBlockEntity() || !isArtificial(level, p, s)) return;
        body.put(x, y, z, OrganBlueprint.M_CARVE);
        if (s.getBlock() instanceof DoorBlock && s.hasProperty(DoorBlock.HALF)) { // 戸は上下とも割る
            BlockPos o = s.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER ? p.above() : p.below();
            if (level.getBlockState(o).is(s.getBlock())) body.put(o.getX(), o.getY(), o.getZ(), OrganBlueprint.M_CARVE);
        }
    }

    /** 根元から壁を這う蔦(空いたマスで、固い物に触れている所だけを進む)。 */
    private static void crawlVine(ServerLevel level, Body body, int sx, int sy, int sz, int steps, int sign, double upW, int cx, int cz, Random rnd) {
        int x = sx, y = sy, z = sz;
        double px = 0, py = 0, pz = 0;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int s = 0; s < steps; s++) {
            int[] best = null;
            double bs = -1.0E9D, bdx = 0, bdy = 0, bdz = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        int qx = x + dx, qy = y + dy, qz = z + dz;
                        long k = BlockPos.asLong(qx, qy, qz);
                        if (seen.contains(k) || body.cells.containsKey(k)) continue;
                        if (level.getChunkSource().getChunkNow(qx >> 4, qz >> 4) == null) continue;
                        BlockState st = level.getBlockState(m.set(qx, qy, qz));
                        if (!(st.isAir() || st.canBeReplaced()) || !st.getFluidState().isEmpty()) continue;
                        boolean touch = false;
                        for (Direction d : Direction.values()) {
                            BlockState n = level.getBlockState(m.set(qx + d.getStepX(), qy + d.getStepY(), qz + d.getStepZ()));
                            if (n.blocksMotion() && !forestPart(n)) {
                                touch = true;
                                break;
                            }
                        }
                        if (!touch) continue;
                        double rx = qx - cx, rz = qz - cz, rl = Math.max(1.0E-3D, Math.hypot(rx, rz));
                        double tx = -rz / rl * sign, tz = rx / rl * sign, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        double sc = (dx * tx + dz * tz) / len * 0.6D + dy / len * upW + (dx * px + dy * py + dz * pz) / len * 0.7D + (rnd.nextDouble() - 0.5D);
                        if (sc > bs) {
                            bs = sc;
                            best = new int[]{qx, qy, qz};
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
            body.put(x, y, z, OrganBlueprint.M_VINE);
        }
    }

    private static double sq(double v) {
        return v * v;
    }
}
