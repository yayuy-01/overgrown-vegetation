package com.yayuy.overgrownvegetation.td;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.InvaderNavigation;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import com.yayuy.overgrownvegetation.raid.EventMobs;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.swarm.Swarm;
import com.yayuy.overgrownvegetation.swarm.SwarmManager;
import com.yayuy.overgrownvegetation.swarm.SwarmRole;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 崖・溝に架ける橋(TD、2026-09-25)。ユーザー報告(runClient 3 回目)「攻める際に崖などがあるとそこでアルボラ以外が立ち往生して
 * しまう。この際他の mob が通れるような橋をアルボラが作れるようにできないか(過繁茂しているブロック overgrown_block などで)。
 * しっかりと G5 mob なども通れるよう幅 3 マスほどの橋を移動経路に合わせて」。
 *
 * <h2>いつ架けるか</h2>
 * <ul>
 *     <li><b>群れ</b>: 攻城(TD の群れは生まれたときから攻城)で区間が続けて塞がれ、TD に壁を選ばせる口({@link TdSiege#pick})で
 *     壁より先に見る({@link #onLegsBlocked})。登れない一員がいる群れ(包絡が登らない = アルボラだけの「登る群れ」以外)で、
 *     アルボラ(群れの一員か、群れの外)がいるときだけ。群れは橋ができるまで区間を引かずに待つ({@link Swarm#holdLegs})。</li>
 *     <li><b>群れの外の攻め手</b>(護衛の G5〜9・群れから外れた一員。{@link TdAssaultGoal}): 進めないとき、近くの橋
 *     (架けている途中なら始まりで待つ)を使う。無ければ頼む(架けるのは近くの群れの外のアルボラだけ)。</li>
 * </ul>
 *
 * <h2>どこに架けるか({@link #plan})</h2>
 * 先導役の足元からターゲットへの主な向き(東西か南北。だめならもう一方、横へ 2・4 列ずらした線も)の直線で、幅
 * {@code bridgeWidth}(td.json)の帯の地面の高さ(木を掘り抜き、水は水面)を後ろ {@value #SCAN_BACK}・前 {@value #SCAN_AHEAD} 列
 * 並べる。前に「2 段以上の上り」か「3 段以上の下り」があれば崖・溝。上りのあと {@value #CLIFF_PERSIST} 列のうちに元の高さへ戻るなら
 * <b>薄い壁</b>とみなして橋にしない(ユーザー指定 2026-09-24「囲った拠点は壁を壊して入る」→ 今までどおり {@link TdSiege} が壊す)。
 * 歩ける高さの並びは「1 列で上るのは 1 段まで・下るのは 2 段まで」で、地面より低くしない(掘らない)いちばん低い並び
 * (後ろから・前からの 2 回で決まる)。崖・溝を含む「地面より高い」区間だけが橋で、区間の両端の地面をまっすぐ結んだ高さへ持ち上げる
 * (溝の上は平らな橋、崖は一定の坂。置く数は同じ)。上りの坂は崖の手前(群れの後ろ)から始まる。
 * <ul>
 *     <li>足場(過繁茂したブロック {@code overgrown_block})は立つ高さの 1 つ下、帯の各列で地面がそれより低い所だけ。浮いた 1 枚の坂
 *     (下は埋めない)。すでに塊のある所(木の幹・葉)はそのまま足場にする。</li>
 *     <li>頭の上 {@value #CLEARANCE} マス(ファルキムネスの高さ)に当たりのある塊(葉・幹・張り出し)は先に崩す({@link TdSiege#strikeWall})。
 *     壊してはいけない塊(ターゲット・岩盤・溶岩に面した塊)があれば、その線は使わない。</li>
 *     <li>置く順・崩す順は群れの側からターゲットの側へ、1 列ずつ(崩してから置く)。</li>
 * </ul>
 *
 * <h2>架け方</h2>
 * 20 tick ではなく {@value #TICK_INTERVAL} tick ごとに、橋の始まりから {@value #RECRUIT_RADIUS} ブロックの侵攻のアルボラ
 * (頼んだ群れの一員 → 群れの外。ほかの群れの一員は取らない = その群れが一員を待って止まる)を {@value #MAX_BUILDERS} 体まで
 * 大工にする。大工は {@link TdBridgeGoal}(優先度 2、群れの Goal より先)で、まだ済んでいない最初の
 * {@value #FRONTIER} 段のうち手の届く段を {@value #BUILD_INTERVAL_TICKS} tick に 1 回置く(崩す段は叩く)。
 * 全部済んだら群れの区間の待ちを戻す(次の区間は橋を渡る)。{@value #STALL_TICKS} tick 1 段も進まない・大工が
 * {@value #NO_BUILDER_TICKS} tick いなければ諦め、その群れは {@value #SKIP_TICKS} tick 橋を頼まない(壁の選び方へ戻る)。
 * できた橋は残す(TD のあとも。消さないのは仮決め)。この表(進み具合)はメモリだけで、再起動・ディメンションの unload・
 * 侵攻の終わりで捨てる(置いた塊は残る)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class TdBridges {

    /** 後ろへ見る列数(上りの坂は崖の高さぶん手前から始まる)。仮決め 2026-09-25。 */
    static final int SCAN_BACK = 24;
    /** 前へ見る列数。仮決め 2026-09-25。 */
    static final int SCAN_AHEAD = 24;
    /** 1 列で上ってよい段(バニラの段上がり)。 */
    static final int MAX_RISE = 1;
    /** 1 列で下りてよい段(落ちても傷まない高さ)。 */
    static final int MAX_DROP = 2;
    /** これ以上の上りが崖(か壁)の始まり。 */
    static final int BARRIER_RISE = 2;
    /** これ以上の下りが崖・溝の始まり。 */
    static final int BARRIER_DROP = 3;
    /** 上りのあと、この列数ずっと高いままなら崖(戻れば薄い壁 = 壊して入る)。仮決め 2026-09-25。 */
    static final int CLIFF_PERSIST = 4;
    /** 橋の上に空ける高さ(マス)。ファルキムネス(背 3 超 = 経路では 4 マス)が通れる。仮決め 2026-09-25。 */
    static final int CLEARANCE = 4;
    /** 1 本の橋で置く塊の上限(これより長い崖は架けない)。仮決め 2026-09-25。 */
    static final int MAX_DECK = 160;
    /** 1 本の橋で崩す塊の上限(森の中で葉を刈りすぎない)。仮決め 2026-09-25。 */
    static final int MAX_CLEAR = 80;
    /** 横へずらして試す列(0 = 先導役の足元の線)。 */
    private static final int[] SHIFTS = {0, 2, -2, 4, -4};
    /** 1 本の橋の大工の数。仮決め 2026-09-25。 */
    static final int MAX_BUILDERS = 6;
    /** 大工を集める半径(橋の始まりから、ブロック)。仮決め 2026-09-25。 */
    static final double RECRUIT_RADIUS = 24.0D;
    /** 大工が 1 段置く(崩す段を 1 回叩く)間隔(tick)。仮決め 2026-09-25。 */
    static final int BUILD_INTERVAL_TICKS = 10;
    /** 大工が一度に手を付ける、まだ済んでいない段の数(先頭から)。 */
    static final int FRONTIER = 8;
    /** 表を見直す間隔(tick)。 */
    static final int TICK_INTERVAL = 10;
    private static final int TICK_PHASE = 3;
    /** 群れの区間を待たせる長さ(見直しのたびに延ばす)。 */
    private static final int HOLD_TICKS = 40;
    /** 1 段も進まなければ諦める(tick)。仮決め 2026-09-25。 */
    static final int STALL_TICKS = 400;
    /** 大工がいないまま、これだけ経ったら諦める(tick)。 */
    static final int NO_BUILDER_TICKS = 200;
    /** できた橋を「近くの橋」として覚えておく間(tick)。 */
    static final int KEEP_TICKS = 2400;
    /** 諦めた群れが橋を頼まない間(tick)。仮決め 2026-09-25。 */
    static final int SKIP_TICKS = 1200;
    /** 橋を引けなかった群れ・攻め手が次に試すまで(tick)。 */
    static final int NO_PLAN_TICKS = 200;
    /** 群れの外の攻め手が使う橋の、始まりまでの距離(ブロック)。仮決め 2026-09-25。 */
    static final double USE_RADIUS = 40.0D;
    /** 使う橋の降り口が、今いる所よりこれだけターゲットに近いこと(ブロック)。 */
    private static final double USE_GAIN = 4.0D;
    /** 置く・崩す手の届く箱(体の箱を横・下・上へ広げる、ブロック)。壁を叩くより広い(足場は宙に置く)。仮決め 2026-09-25。 */
    static final double REACH_SIDE = 1.5D;
    static final double REACH_DOWN = 2.5D;
    static final double REACH_UP = 2.0D;

    private static final Map<ResourceKey<Level>, LevelBridges> LEVELS = new HashMap<>();

    // ------------------------------------------------------------------ 計画

    /** 置く(true)か崩す(false)1 段。 */
    public record Step(BlockPos pos, boolean place) {
    }

    /**
     * 橋の計画。
     *
     * @param dir     架ける向き(群れの側 → ターゲットの側)
     * @param start   渡り始めの地面(立つ位置、帯の中心の列)
     * @param landing 渡り終えの地面(立つ位置)
     * @param steps   置く・崩す順
     * @param deck    置く塊の数
     */
    public record Plan(Direction dir, BlockPos start, BlockPos landing, List<Step> steps, int deck) {
    }

    /**
     * 計画の見る範囲(後ろ・前の列数、崖とみなす高いままの列数、頭の上に空ける高さ)。ふだんは {@link #DEFAULT}。
     * GameTest のテスト場(7 × 7 × 7、外と天井はバリア)に収めるときだけ小さくする。
     */
    record Shape(int back, int ahead, int persist, int clearance,
                 @Nullable net.minecraft.world.level.levelgen.structure.BoundingBox clip) {
        static final Shape DEFAULT = new Shape(SCAN_BACK, SCAN_AHEAD, CLIFF_PERSIST, CLEARANCE, null);

        /** 地面の高さを見る列の x(テスト用の箱があれば箱の中へ寄せる = 箱の外の列は縁の列と同じ高さとみなす)。 */
        int clipX(int x) {
            return this.clip == null ? x : Math.max(this.clip.minX(), Math.min(this.clip.maxX(), x));
        }

        int clipZ(int z) {
            return this.clip == null ? z : Math.max(this.clip.minZ(), Math.min(this.clip.maxZ(), z));
        }
    }

    /** テスト用: 群れ・攻め手が頼む橋の見る範囲の差し替え(null = ふつう)。サーバー停止で戻す。 */
    @Nullable
    private static Shape shapeForTests;

    private static Shape shape() {
        return shapeForTests != null ? shapeForTests : Shape.DEFAULT;
    }

    /** テスト用: 見る範囲を差し替える(null で戻す)。 */
    static void setShapeForTests(@Nullable Shape shape) {
        shapeForTests = shape;
    }

    /**
     * {@code feet}(足の位置)から {@code goal} の向きの崖・溝に架ける橋。前に崖・溝が無い・薄い壁・長すぎる・壊せない塊が
     * 邪魔をする・読み込まれていない列があるときは null。{@code target}(侵攻のターゲット)は崩さない。
     */
    @Nullable
    public static Plan plan(ServerLevel level, BlockPos feet, BlockPos goal, @Nullable BlockPos target, int width) {
        return plan(level, feet, goal, target, width, Shape.DEFAULT);
    }

    @Nullable
    static Plan plan(ServerLevel level, BlockPos feet, BlockPos goal, @Nullable BlockPos target, int width, Shape shape) {
        for (Direction dir : directions(feet, goal)) {
            for (int shift : SHIFTS) {
                Plan plan = planAlong(level, feet, dir, shift, width, target, shape);
                if (plan != null) {
                    return plan;
                }
            }
        }
        return null;
    }

    /** ターゲットへの主な向き(東西・南北の大きい方)、次にもう一方(ずれが無ければ試さない)。 */
    private static List<Direction> directions(BlockPos feet, BlockPos goal) {
        int dx = goal.getX() - feet.getX();
        int dz = goal.getZ() - feet.getZ();
        Direction ex = dx >= 0 ? Direction.EAST : Direction.WEST;
        Direction ez = dz >= 0 ? Direction.SOUTH : Direction.NORTH;
        List<Direction> out = new ArrayList<>(2);
        if (Math.abs(dx) >= Math.abs(dz)) {
            out.add(ex);
            if (dz != 0) {
                out.add(ez);
            }
        } else {
            out.add(ez);
            if (dx != 0) {
                out.add(ex);
            }
        }
        return out;
    }

    @Nullable
    private static Plan planAlong(ServerLevel level, BlockPos feet, Direction dir, int shift, int width,
                                  @Nullable BlockPos target, Shape shape) {
        int lo = -(width - 1) / 2;
        int lanes = width;
        int back = shape.back();
        int n = back + shape.ahead() + 1;
        Direction side = dir.getClockWise();
        int sx = side.getStepX();
        int sz = side.getStepZ();
        int bx = feet.getX() + sx * shift;
        int bz = feet.getZ() + sz * shift;
        int[][] lane = new int[n][lanes];
        int[] g = new int[n];
        for (int i = 0; i < n; i++) {
            int k = i - back;
            int cx = bx + dir.getStepX() * k;
            int cz = bz + dir.getStepZ() * k;
            int max = Integer.MIN_VALUE;
            for (int l = 0; l < lanes; l++) {
                int o = lo + l;
                Integer y = standY(level, shape.clipX(cx + sx * o), shape.clipZ(cz + sz * o), shape.clip());
                if (y == null) {
                    return null;
                }
                lane[i][l] = y;
                max = Math.max(max, y);
            }
            g[i] = max;
        }

        // 前の崖・溝(足元の列から前へ)。
        int barrier = -1;
        boolean up = false;
        for (int i = back + 1; i < n; i++) {
            int rise = g[i] - g[i - 1];
            if (rise >= BARRIER_RISE) {
                barrier = i;
                up = true;
                break;
            }
            if (-rise >= BARRIER_DROP) {
                barrier = i;
                break;
            }
        }
        if (barrier < 0) {
            return null;
        }
        if (up) {
            // 上ってすぐ戻る = 薄い壁(拠点の壁)。壊して入る(TdSiege)。
            int base = g[barrier - 1];
            if (barrier + shape.persist() > n) {
                return null;
            }
            for (int i = barrier; i < barrier + shape.persist(); i++) {
                if (g[i] < base + BARRIER_RISE) {
                    return null;
                }
            }
        }

        // 歩ける高さ: 上りは 1 列 1 段(後ろへ伝える)、下りは 1 列 2 段(前へ伝える)。地面より低くしない。
        int[] s = g.clone();
        for (int i = n - 1; i > 0; i--) {
            s[i - 1] = Math.max(s[i - 1], s[i] - MAX_RISE);
        }
        for (int i = 1; i < n; i++) {
            s[i] = Math.max(s[i], s[i - 1] - MAX_DROP);
        }
        int j = up ? barrier - 1 : barrier;
        if (s[j] <= g[j]) {
            return null;
        }
        int a = j;
        while (a - 1 >= 0 && s[a - 1] > g[a - 1]) {
            a--;
        }
        int b = j;
        while (b + 1 < n && s[b + 1] > g[b + 1]) {
            b++;
        }
        if (a == 0 || b == n - 1) {
            return null; // 見た範囲に収まらない(崖が高すぎる・溝が広すぎる)
        }
        // 両端の地面をまっすぐ結んだ高さへ持ち上げる(溝の上は平ら。上り 1・下り 2 の決まりは崩れない)。
        int from = g[a - 1];
        int to = g[b + 1];
        int span = b - a + 2;
        for (int i = a; i <= b; i++) {
            double t = (double) (i - (a - 1)) / span;
            s[i] = Math.max(s[i], (int) Math.round(from + (to - from) * t));
        }

        int[] order = laneOrder(lo, lanes);
        List<Step> steps = new ArrayList<>();
        int deck = 0;
        int clear = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = a - 1; i <= b + 1; i++) {
            int k = i - back;
            int cx = bx + dir.getStepX() * k;
            int cz = bz + dir.getStepZ() * k;
            int stand = s[i];
            // 頭の上(上から崩す)。
            for (int l : order) {
                int o = lo + l;
                for (int y = stand + shape.clearance() - 1; y >= stand; y--) {
                    pos.set(cx + sx * o, y, cz + sz * o);
                    BlockState state = loadedState(level, pos);
                    if (state == null) {
                        return null;
                    }
                    if (state.getCollisionShape(level, pos).isEmpty()) {
                        continue;
                    }
                    if (!TdSiege.breakable(level, pos, state, target)) {
                        return null;
                    }
                    steps.add(new Step(pos.immutable(), false));
                    clear++;
                }
            }
            // 足場(帯の中心から)。
            for (int l : order) {
                if (lane[i][l] >= stand) {
                    continue; // その列の地面がもう立つ高さ
                }
                int o = lo + l;
                pos.set(cx + sx * o, stand - 1, cz + sz * o);
                BlockState state = loadedState(level, pos);
                if (state == null) {
                    return null;
                }
                if (!state.getCollisionShape(level, pos).isEmpty()) {
                    continue; // 幹・葉など、もう踏める塊がある
                }
                if (!placeable(level, pos, state, target)) {
                    return null;
                }
                steps.add(new Step(pos.immutable(), true));
                deck++;
            }
        }
        if (deck == 0 || deck > MAX_DECK || clear > MAX_CLEAR) {
            return null;
        }
        int ka = a - 1 - back;
        int kb = b + 1 - back;
        BlockPos start = new BlockPos(bx + dir.getStepX() * ka, s[a - 1], bz + dir.getStepZ() * ka);
        BlockPos landing = new BlockPos(bx + dir.getStepX() * kb, s[b + 1], bz + dir.getStepZ() * kb);
        return new Plan(dir, start, landing, List.copyOf(steps), deck);
    }

    /** 帯の列の添え字を中心に近い順に。 */
    private static int[] laneOrder(int lo, int lanes) {
        Integer[] idx = new Integer[lanes];
        for (int l = 0; l < lanes; l++) {
            idx[l] = l;
        }
        java.util.Arrays.sort(idx, Comparator.comparingInt((Integer l) -> Math.abs(lo + l)).thenComparingInt(l -> lo + l < 0 ? 1 : 0));
        int[] out = new int[lanes];
        for (int l = 0; l < lanes; l++) {
            out[l] = idx[l];
        }
        return out;
    }

    /** 足場を置いてよいマス: 空気・水・置き換えられる物(草・雪)・当たりの無い壊してよい物(松明・看板)。溶岩は置かない。 */
    private static boolean placeable(ServerLevel level, BlockPos pos, BlockState state, @Nullable BlockPos target) {
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty() && !fluid.is(FluidTags.WATER)) {
            return false;
        }
        return state.isAir() || state.canBeReplaced() || TdSiege.breakable(level, pos, state, target);
    }

    /**
     * (x, z) 列の立つ高さ: 木の天蓋・幹を掘り抜いた地面の 1 つ上、そこが水なら水面の上(水の上は橋を架けない = 泳いで渡る)。
     * 読み込まれていなければ null(読み込まない)。
     */
    @Nullable
    private static Integer standY(ServerLevel level, int x, int z,
                                  @Nullable net.minecraft.world.level.levelgen.structure.BoundingBox clip) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) {
            return null;
        }
        BlockPos ground = clip == null ? TreeCanopyGroundFinder.findSpawnSurface(level, x, z) : groundBelow(chunk, x, z, clip);
        if (ground == null) {
            return null;
        }
        BlockPos.MutableBlockPos p = ground.mutable();
        for (int guard = 0; guard < 64 && !chunk.getFluidState(p).isEmpty(); guard++) {
            p.move(Direction.UP);
        }
        return p.getY();
    }

    /**
     * テスト用の箱の中だけで地面を探す(箱の上の段から下へ、木でない動きを止める塊の 1 つ上)。GameTest のテスト場は天井がバリアで、
     * 高さの地図({@link TreeCanopyGroundFinder#findSpawnSurface} の出発点)はどの列もその上を答えるため。
     */
    @Nullable
    private static BlockPos groundBelow(LevelChunk chunk, int x, int z,
                                        net.minecraft.world.level.levelgen.structure.BoundingBox clip) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, clip.maxY(), z);
        for (int y = clip.maxY(); y > clip.minY(); y--) {
            p.setY(y - 1);
            BlockState below = chunk.getBlockState(p);
            if (below.blocksMotion() && !TreeCanopyGroundFinder.isTreePart(below)) {
                return new BlockPos(x, y, z);
            }
        }
        return null;
    }

    @Nullable
    private static BlockState loadedState(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk != null ? chunk.getBlockState(pos) : null;
    }

    // ------------------------------------------------------------------ 群れ・攻め手から

    /**
     * 群れの区間が続けて塞がれた(TD に壁を選ばせる口 {@link TdSiege#pick} から、20 tick に 1 回まで)。架けている途中の橋が
     * あればそれを待ち、無ければ前の崖・溝に橋を計画する。
     *
     * @return 橋を待っている(壁は選ばない。群れは区間を引かずに待つ)
     */
    static boolean onLegsBlocked(ServerLevel level, Mob vanguard, Swarm swarm, BlockPos target, long now) {
        LevelBridges st = state(level);
        Project existing = st.bySwarm.get(swarm.id());
        if (existing != null && !existing.finished()) {
            return true;
        }
        if (swarm.climber() || swarm.kind() != Swarm.Kind.GROUND) {
            return false; // 登る群れは崖を登る
        }
        InvaderNavigation.Envelope envelope = swarm.envelope();
        if (envelope != null && envelope.canClimb()) {
            return false;
        }
        Long skip = st.swarmSkip.get(swarm.id());
        if (skip != null && skip > now) {
            return false;
        }
        String eventId = TdEvents.activeEventId(level);
        if (eventId == null || !hasBuilder(level, swarm, vanguard.position(), eventId)) {
            st.swarmSkip.put(swarm.id(), now + NO_PLAN_TICKS);
            return false;
        }
        Plan plan = plan(level, vanguard.blockPosition(), target, target, DesignData.td().bridgeWidth(), shape());
        if (plan == null) {
            st.swarmSkip.put(swarm.id(), now + NO_PLAN_TICKS);
            return false;
        }
        Project project = st.overlapping(plan);
        if (project == null) {
            project = new Project(plan, now);
            st.projects.add(project);
            OvergrownVegetation.LOGGER.debug("TD: 橋を計画した({} → {}、足場 {} 個・段 {})", plan.start(), plan.landing(),
                    plan.deck(), plan.steps().size());
        }
        project.swarms.add(swarm.id());
        st.bySwarm.put(swarm.id(), project);
        swarm.holdLegs(now + HOLD_TICKS);
        swarm.holdSiegeArrival(now + HOLD_TICKS);
        return true;
    }

    /** 群れにアルボラがいるか、近くに群れの外のアルボラ(侵攻の印付き)がいるか。 */
    private static boolean hasBuilder(ServerLevel level, Swarm swarm, Vec3 around, String eventId) {
        for (Mob member : swarm.activeMembers()) {
            if (member.isAlive() && SwarmRole.isArbora(member.getType())) {
                return true;
            }
        }
        return !level.getEntitiesOfClass(Mob.class, new AABB(around, around).inflate(RECRUIT_RADIUS),
                m -> candidate(m, eventId) && SwarmManager.get(m) == null).isEmpty();
    }

    /** 群れの外の攻め手が渡る橋(始まりと降り口、できあがっているか)。 */
    public record Crossing(BlockPos start, BlockPos landing, boolean ready) {
    }

    /**
     * 群れの外の攻め手({@link TdAssaultGoal})が進めないときに使う橋: 始まりが {@value #USE_RADIUS} 以内で、降り口が今いる所より
     * ターゲットに近い、できあがった(か架けている途中の)橋。無ければ前の崖・溝に橋を頼む(架けるのは近くの群れの外のアルボラ)。
     * どちらも無ければ null(壁を崩す今までの手へ)。
     */
    @Nullable
    static Crossing crossingFor(ServerLevel level, Mob mob, BlockPos target, long now) {
        LevelBridges st = state(level);
        Vec3 at = mob.position();
        double here = horizontalSq(at, Vec3.atCenterOf(target));
        Project best = null;
        double bestSq = USE_RADIUS * USE_RADIUS;
        for (Project p : st.projects) {
            if (p.finished() && !p.completed) {
                continue;
            }
            double d = horizontalSq(at, Vec3.atBottomCenterOf(p.plan.start()));
            double gain = Math.sqrt(here) - Math.sqrt(horizontalSq(Vec3.atBottomCenterOf(p.plan.landing()), Vec3.atCenterOf(target)));
            if (d <= bestSq && gain >= USE_GAIN) {
                bestSq = d;
                best = p;
            }
        }
        if (best == null) {
            Long skip = st.mobSkip.get(mob.getId());
            if (skip != null && skip > now) {
                return null;
            }
            st.mobSkip.put(mob.getId(), now + NO_PLAN_TICKS);
            String eventId = TdEvents.activeEventId(level);
            if (eventId == null || level.getEntitiesOfClass(Mob.class, new AABB(at, at).inflate(RECRUIT_RADIUS),
                    m -> candidate(m, eventId) && SwarmManager.get(m) == null).isEmpty()) {
                return null;
            }
            Plan plan = plan(level, mob.blockPosition(), target, target, DesignData.td().bridgeWidth(), shape());
            if (plan == null) {
                return null;
            }
            best = st.overlapping(plan);
            if (best == null) {
                best = new Project(plan, now);
                st.projects.add(best);
            }
        }
        return new Crossing(best.plan.start(), best.plan.landing(), best.completed);
    }

    // ------------------------------------------------------------------ 大工({@link TdBridgeGoal})

    /** 大工の 1 段(どの橋のどの段か)。 */
    public record Task(Project project, Step step) {
        public BlockPos pos() {
            return this.step.pos();
        }
    }

    /** その Mob が橋の大工か。 */
    static boolean isBuilder(Mob mob) {
        if (!(mob.level() instanceof ServerLevel level)) {
            return false;
        }
        LevelBridges st = LEVELS.get(level.dimension());
        if (st == null) {
            return false;
        }
        Project p = st.builderOf.get(mob.getUUID());
        return p != null && !p.finished();
    }

    /** 大工が次に手を付ける段: まだ済んでいない最初の {@value #FRONTIER} 段のうち手の届く段、無ければその先頭(寄っていく)。 */
    @Nullable
    static Task task(Mob mob) {
        if (!(mob.level() instanceof ServerLevel level)) {
            return null;
        }
        LevelBridges st = LEVELS.get(level.dimension());
        Project p = st != null ? st.builderOf.get(mob.getUUID()) : null;
        if (p == null || p.finished()) {
            return null;
        }
        List<Step> steps = p.plan.steps();
        Step first = null;
        int seen = 0;
        for (int i = p.next; i < steps.size() && seen < FRONTIER; i++) {
            Step step = steps.get(i);
            if (satisfied(level, step)) {
                continue;
            }
            seen++;
            if (first == null) {
                first = step;
            }
            if (canReach(mob, step.pos())) {
                return new Task(p, step);
            }
        }
        return first != null ? new Task(p, first) : null;
    }

    /** 置く・崩す手が届くか(体の箱を横 {@value #REACH_SIDE}・下 {@value #REACH_DOWN}・上 {@value #REACH_UP} 広げて重なる)。 */
    static boolean canReach(Mob mob, BlockPos pos) {
        AABB box = mob.getBoundingBox();
        AABB reach = new AABB(box.minX - REACH_SIDE, box.minY - REACH_DOWN, box.minZ - REACH_SIDE,
                box.maxX + REACH_SIDE, box.maxY + REACH_UP, box.maxZ + REACH_SIDE);
        return reach.intersects(new AABB(pos));
    }

    /**
     * 1 段ぶん手を動かす。置く段は過繁茂したブロックを置き(中に立っている者は上へ押し出す)、崩す段は壁と同じ量だけ叩く
     * ({@link TdSiege#strikeWall}。葉は 2 回、幹は 3 回ほど)。
     *
     * @return 手を動かした(攻撃のアニメを付ける)
     */
    static boolean act(ServerLevel level, Mob builder, Task task) {
        Step step = task.step();
        BlockPos pos = step.pos();
        BlockState state = loadedState(level, pos);
        if (state == null) {
            return false;
        }
        long now = level.getGameTime();
        if (!step.place()) {
            TdSiege.WallStrike result = TdSiege.strikeWall(builder, pos);
            if (result == TdSiege.WallStrike.INVALID) {
                return false;
            }
            task.project().lastProgress = now;
            return true;
        }
        if (!state.getCollisionShape(level, pos).isEmpty()) {
            return false; // もう塊がある(誰かが置いた)
        }
        BlockPos target = TdEvents.activeTarget(level);
        if (!placeable(level, pos, state, target)) {
            return false;
        }
        if (!state.isAir() && !state.canBeReplaced()) {
            level.destroyBlock(pos, state.hasBlockEntity(), builder); // 松明・看板など(持ち物は落とす)
        }
        BlockState deck = ModBlocks.OVERGROWN_BLOCK.get().defaultBlockState();
        deck = Block.pushEntitiesUp(level.getBlockState(pos), deck, level, pos);
        level.setBlock(pos, deck, Block.UPDATE_ALL);
        level.playSound(null, pos, deck.getSoundType(level, pos, builder).getPlaceSound(), SoundSource.HOSTILE, 1.0F, 0.8F);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, deck), pos.getX() + 0.5D, pos.getY() + 0.5D,
                pos.getZ() + 0.5D, 8, 0.3D, 0.3D, 0.3D, 0.05D);
        task.project().lastProgress = now;
        return true;
    }

    /** その段が済んでいるか(置く段は当たりのある塊がある、崩す段は当たりが無い)。読み込まれていなければ済んでいない。 */
    private static boolean satisfied(ServerLevel level, Step step) {
        BlockState state = loadedState(level, step.pos());
        if (state == null) {
            return false;
        }
        boolean solid = !state.getCollisionShape(level, step.pos()).isEmpty();
        return step.place() == solid;
    }

    // ------------------------------------------------------------------ 見直し

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        LevelBridges st = LEVELS.get(level.dimension());
        if (st == null) {
            return;
        }
        long now = level.getGameTime();
        if ((now + TICK_PHASE) % TICK_INTERVAL != 0L) {
            return;
        }
        String eventId = TdEvents.activeEventId(level);
        if (eventId == null) {
            LEVELS.remove(level.dimension()); // 侵攻が終わった(置いた塊は残る)
            return;
        }
        for (Iterator<Project> it = st.projects.iterator(); it.hasNext(); ) {
            Project p = it.next();
            if (p.finished()) {
                if (now - p.finishedAt > KEEP_TICKS) {
                    it.remove();
                }
                continue;
            }
            update(level, st, p, eventId, now);
        }
        st.swarmSkip.values().removeIf(until -> until <= now);
        st.mobSkip.values().removeIf(until -> until <= now);
        if (st.projects.isEmpty() && st.swarmSkip.isEmpty() && st.mobSkip.isEmpty()) {
            LEVELS.remove(level.dimension());
        }
    }

    private static void update(ServerLevel level, LevelBridges st, Project p, String eventId, long now) {
        List<Step> steps = p.plan.steps();
        int next = 0;
        while (next < steps.size() && satisfied(level, steps.get(next))) {
            next++;
        }
        int done = next;
        for (int i = next; i < steps.size(); i++) {
            if (satisfied(level, steps.get(i))) {
                done++;
            }
        }
        if (done > p.done) {
            p.lastProgress = now;
        }
        p.done = done;
        p.next = next;
        if (next >= steps.size()) {
            finish(level, st, p, now, true);
            return;
        }
        if (now - p.lastProgress > STALL_TICKS) {
            finish(level, st, p, now, false);
            return;
        }
        for (UUID id : p.swarms) {
            Swarm swarm = SwarmManager.get(level, id);
            if (swarm != null) {
                swarm.holdLegs(now + HOLD_TICKS);
                // 架けている間は塊に着いたことにしない(崖の下で着いた先導役も、できあがったら区間で橋を渡る)。
                swarm.holdSiegeArrival(now + HOLD_TICKS);
            }
        }
        recruit(level, st, p, eventId);
        if (p.builders.isEmpty()) {
            if (p.noBuilderSince < 0L) {
                p.noBuilderSince = now;
            } else if (now - p.noBuilderSince > NO_BUILDER_TICKS) {
                finish(level, st, p, now, false);
            }
        } else {
            p.noBuilderSince = -1L;
        }
    }

    /** 大工を集め直す(いなくなった・遠すぎる大工は外す)。 */
    private static void recruit(ServerLevel level, LevelBridges st, Project p, String eventId) {
        Vec3 centre = Vec3.atBottomCenterOf(p.plan.start());
        double keepSq = (RECRUIT_RADIUS * 2.0D) * (RECRUIT_RADIUS * 2.0D);
        for (Iterator<UUID> it = p.builders.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            Entity entity = level.getEntity(id);
            if (!(entity instanceof Mob mob) || !candidate(mob, eventId) || mob.distanceToSqr(centre) > keepSq) {
                it.remove();
                st.builderOf.remove(id);
            }
        }
        if (p.builders.size() >= MAX_BUILDERS) {
            return;
        }
        List<Mob> found = level.getEntitiesOfClass(Mob.class, new AABB(centre, centre).inflate(RECRUIT_RADIUS),
                m -> candidate(m, eventId) && !st.builderOf.containsKey(m.getUUID()));
        List<Mob> vanguards = new ArrayList<>(2);
        found.removeIf(m -> {
            Swarm swarm = SwarmManager.get(m);
            if (swarm == null) {
                return false; // 群れの外
            }
            if (!p.swarms.contains(swarm.id())) {
                return true; // ほかの群れの一員は取らない(その群れが一員を待って止まる)
            }
            if (swarm.isVanguard(m)) {
                vanguards.add(m); // 先導役はほかに誰もいないときだけ
                return true;
            }
            return false;
        });
        found.sort(Comparator.comparingDouble(m -> m.distanceToSqr(centre)));
        for (Mob mob : found) {
            if (p.builders.size() >= MAX_BUILDERS) {
                break;
            }
            assign(st, p, mob);
        }
        if (p.builders.isEmpty()) {
            for (Mob mob : vanguards) {
                assign(st, p, mob);
                break;
            }
        }
    }

    private static void assign(LevelBridges st, Project p, Mob mob) {
        p.builders.add(mob.getUUID());
        st.builderOf.put(mob.getUUID(), p);
        if (mob instanceof net.minecraft.world.entity.PathfinderMob pathfinder) {
            TdBridgeGoal.install(pathfinder);
        }
    }

    /** 大工になれる: 生きて動く、侵攻の印付きのアルボラ。 */
    private static boolean candidate(Mob mob, String eventId) {
        return mob.isAlive() && !mob.isNoAi() && SwarmRole.isArbora(mob.getType())
                && EventMobs.has(mob, EventMobs.Kind.TD, eventId);
    }

    /** 橋を終える(できた・諦めた)。大工を放し、待たせていた群れの区間を戻す。諦めたら、その群れはしばらく橋を頼まない。 */
    private static void finish(ServerLevel level, LevelBridges st, Project p, long now, boolean completed) {
        p.finishedAt = now;
        p.completed = completed;
        for (UUID id : p.builders) {
            st.builderOf.remove(id);
        }
        p.builders.clear();
        for (UUID id : p.swarms) {
            st.bySwarm.remove(id);
            Swarm swarm = SwarmManager.get(level, id);
            if (swarm != null) {
                swarm.resumeLegs();
            }
            if (!completed) {
                st.swarmSkip.put(id, now + SKIP_TICKS);
            }
        }
        OvergrownVegetation.LOGGER.debug("TD: 橋を{}({} → {})", completed ? "架け終えた" : "諦めた", p.plan.start(),
                p.plan.landing());
    }

    private static double horizontalSq(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    // ------------------------------------------------------------------ 状態

    /** 1 本の橋(メモリだけ)。 */
    public static final class Project {
        final Plan plan;
        /** 待たせている群れ。 */
        final Set<UUID> swarms = new HashSet<>();
        /** 大工(Mob の UUID)。 */
        final Set<UUID> builders = new LinkedHashSet<>();
        /** まだ済んでいない最初の段。 */
        int next;
        /** 済んでいる段の数。 */
        int done;
        long lastProgress;
        long noBuilderSince = -1L;
        long finishedAt = -1L;
        boolean completed;

        Project(Plan plan, long now) {
            this.plan = plan;
            this.lastProgress = now;
        }

        boolean finished() {
            return this.finishedAt >= 0L;
        }

        public Plan plan() {
            return this.plan;
        }

        public boolean completed() {
            return this.completed;
        }

        public int builderCount() {
            return this.builders.size();
        }
    }

    private static final class LevelBridges {
        final List<Project> projects = new ArrayList<>();
        final Map<UUID, Project> bySwarm = new HashMap<>();
        final Map<UUID, Project> builderOf = new HashMap<>();
        final Map<UUID, Long> swarmSkip = new HashMap<>();
        final Map<Integer, Long> mobSkip = new HashMap<>();

        /** 同じ向きで始まりが 8 ブロック以内の、架けている途中の橋(二重に架けない)。 */
        @Nullable
        Project overlapping(Plan plan) {
            for (Project p : this.projects) {
                if (!p.finished() && p.plan.dir() == plan.dir() && p.plan.start().distSqr(plan.start()) <= 64.0D) {
                    return p;
                }
            }
            return null;
        }
    }

    private static LevelBridges state(ServerLevel level) {
        return LEVELS.computeIfAbsent(level.dimension(), k -> new LevelBridges());
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LEVELS.remove(level.dimension());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LEVELS.clear();
        shapeForTests = null;
    }

    // ------------------------------------------------------------------ テスト用

    /** テスト用: 見る範囲を小さくした計画(テスト場に収める)。 */
    @Nullable
    static Plan planForTests(ServerLevel level, BlockPos feet, BlockPos goal, @Nullable BlockPos target, int width,
                             int back, int ahead, int persist, int clearance,
                             @Nullable net.minecraft.world.level.levelgen.structure.BoundingBox clip) {
        return plan(level, feet, goal, target, width, new Shape(back, ahead, persist, clearance, clip));
    }

    /** テスト用: {@link #planForTests} の橋を架け始める(群れは待たせない。大工は群れの外のアルボラ)。計画できなければ null。 */
    @Nullable
    static Project startForTests(ServerLevel level, BlockPos feet, BlockPos goal, int width, int back, int ahead,
                                 int persist, int clearance,
                                 @Nullable net.minecraft.world.level.levelgen.structure.BoundingBox clip) {
        Plan plan = planForTests(level, feet, goal, TdEvents.activeTarget(level), width, back, ahead, persist, clearance, clip);
        if (plan == null) {
            return null;
        }
        Project p = new Project(plan, level.getGameTime());
        state(level).projects.add(p);
        return p;
    }

    /** テスト用: その群れが待っている橋(無ければ null)。 */
    @Nullable
    static Project projectOf(ServerLevel level, UUID swarm) {
        LevelBridges st = LEVELS.get(level.dimension());
        return st != null ? st.bySwarm.get(swarm) : null;
    }

    private TdBridges() {
    }
}
