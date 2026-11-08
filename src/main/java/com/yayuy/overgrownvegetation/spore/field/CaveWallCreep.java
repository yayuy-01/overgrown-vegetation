package com.yayuy.overgrownvegetation.spore.field;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.corruption.CorruptionSwitch;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 洞窟の壁を這う汚染(2026-11-04 暴露量ノート 追記 6【決定】b。10-22 の「地中のブロックも徐々に汚染され、入り口のない洞窟にも広がる」)。
 *
 * <p>汚染された洞窟の壁(空気に面した、地面より下のブロック)を<b>元</b>として待ち行列に持ち、{@code underground.creepIntervalTicks} ごとに
 * 先頭から {@code creepSourcesPerPass} 個を見る。元のまわり 26 マスのうち「汚染されていない・空気に面した・地面より下・汚染してよい」ものを 1 つ選び、
 * {@code creepChance} の確率で汚染する(置き換え・覆いは {@link CorruptionRegistry} の変換と覆いのまま。新しい見た目は無い)。
 * 汚染できたマスは新しい元になる。チャンクの境に関係なく実際の洞窟に沿って伸び、つながっていれば入り口の無い洞窟にも届く。
 * 空気に面していない石(壁の奥)には広がらない。
 *
 * <p>元の見つけ方: 洞窟の床からの発芽で置いた芽の点({@link SporeSprouts})と、プレイヤーのまわり {@code creepRadius} ブロックの洞窟の床を
 * 毎回 {@code creepSamplesPerPass} 列ずつ見て汚染された床を拾う。読み込まれたチャンクだけで動く(読み込まれていない元は捨てる。拾い直す)。
 * 待ち行列は保存しない(仮決め)。重さは {@link TickBudget} の {@link PerfJob#CAVE_WALL_CREEP}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class CaveWallCreep {

    private static final Map<ResourceKey<Level>, LongLinkedOpenHashSet> FRONTIER = new HashMap<>();

    private CaveWallCreep() {
    }

    private static LongLinkedOpenHashSet frontier(Level level) {
        return FRONTIER.computeIfAbsent(level.dimension(), k -> new LongLinkedOpenHashSet());
    }

    /** 汚染された洞窟の壁を元として足す(条件に合わなければ何もしない)。 */
    public static void seed(ServerLevel level, BlockPos pos) {
        if (!(level.dimension() == Level.OVERWORLD) || !loaded(level, pos)) return;
        BlockState state = level.getBlockState(pos);
        if (!OvergrownLayerData.isCorruptedCell(level, pos, state) || !airFacing(level, pos) || !underground(level, pos)) return;
        LongLinkedOpenHashSet f = frontier(level);
        if (f.size() < DesignData.sporeField().creepMaxFrontier()) f.add(pos.asLong());
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(level.dimension() == Level.OVERWORLD) || CorruptionSwitch.isOff()) return;
        SporeFieldTable t = DesignData.sporeField();
        if (!t.enabled() || t.creepIntervalTicks() <= 0 || level.getGameTime() % t.creepIntervalTicks() != 0) return;
        if (level.players().isEmpty() || !TickBudget.mayStart(PerfJob.CAVE_WALL_CREEP)) return;
        long start = TickBudget.begin();
        int done = 0;
        try {
            sample(level, t);
            done = pass(level, t, true);
        } finally {
            TickBudget.end(PerfJob.CAVE_WALL_CREEP, start, done);
        }
    }

    /** プレイヤーのまわりの洞窟の床を何列か見て、汚染された床を元に拾う。 */
    private static void sample(ServerLevel level, SporeFieldTable t) {
        RandomSource random = level.random;
        int r = t.creepRadius();
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            for (int i = 0; i < t.creepSamplesPerPass(); i++) {
                int x = p.getBlockX() + random.nextInt(2 * r + 1) - r, z = p.getBlockZ() + random.nextInt(2 * r + 1) - r;
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) continue;
                BlockPos floor = SporeSprouts.caveFloor(level, chunk, x, z, random);
                if (floor != null) seed(level, floor.below());
            }
        }
    }

    /** 先頭から元を見て広げる。見た元の数を返す。{@code budgeted} = 予算の残りで打ち切る。 */
    private static int pass(ServerLevel level, SporeFieldTable t, boolean budgeted) {
        LongLinkedOpenHashSet f = frontier(level);
        RandomSource random = level.random;
        int n = Math.min(t.creepSourcesPerPass(), f.size());
        int done = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        List<BlockPos> cand = new ArrayList<>(26);
        for (int k = 0; k < n && !f.isEmpty(); k++) {
            if (budgeted && done > 0 && !TickBudget.timeLeft()) break;
            BlockPos src = BlockPos.of(f.removeFirstLong());
            done++;
            if (!loaded(level, src) || !OvergrownLayerData.isCorruptedCell(level, src, level.getBlockState(src))) continue;
            cand.clear();
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        m.set(src.getX() + dx, src.getY() + dy, src.getZ() + dz);
                        if (candidate(level, m)) cand.add(m.immutable());
                    }
            if (cand.isEmpty()) continue; // 囲まれた: もう元ではない
            f.add(src.asLong()); // まだ広げる先がある → 後ろへ回す
            if (random.nextDouble() >= t.creepChance()) continue;
            BlockPos to = cand.get(random.nextInt(cand.size()));
            if (corrupt(level, to, random) && f.size() < t.creepMaxFrontier()) f.add(to.asLong());
        }
        return done;
    }

    /** 這う先にしてよいか: 読み込まれている・汚染されていない・空気に面した・地面より下・汚染してよい。 */
    static boolean candidate(ServerLevel level, BlockPos pos) {
        if (!loaded(level, pos) || level.isOutsideBuildHeight(pos)) return false;
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()) return false;
        if (OvergrownLayerData.isCorruptedCell(level, pos, state)) return false;
        return airFacing(level, pos) && underground(level, pos) && CorruptionRegistry.canCorrupt(level, pos, state);
    }

    /** 汚染する(通常の変換。置き換えの無いものは覆いになる)。汚染済みになれば true。 */
    private static boolean corrupt(ServerLevel level, BlockPos pos, RandomSource random) {
        BlockState state = level.getBlockState(pos);
        BlockState corrupted = CorruptionRegistry.corrupt(level, pos, state, random);
        if (!CorruptionRegistry.noChange(state, corrupted)) {
            CorruptionRegistry.placeCorrupted(level, pos, state, corrupted, Block.UPDATE_ALL);
            com.yayuy.overgrownvegetation.phase.CorruptionCounter.record(level, 1);
            // 洞窟の壁からも壁と壁の隙間に太い蔦の抽選(11-07 ユーザー決定「洞窟にも出す」。確率は vineSpanCaveChance(11-07 ユーザー決定「洞窟だけ確率を上げる」)、天井の決まりはそのまま)
            com.yayuy.overgrownvegetation.corruption.VineSpan.tryGrowCave(level, pos, random);
        }
        return OvergrownLayerData.isCorruptedCell(level, pos, level.getBlockState(pos));
    }

    private static boolean loaded(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    /** 6 方のどれかが空気(洞窟の空間)か。 */
    static boolean airFacing(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.values()) {
            m.setWithOffset(pos, d);
            if (loaded(level, m) && level.getBlockState(m).isAir()) return true;
        }
        return false;
    }

    /** 地面より下か(梢・幹をくぐった本当の地面より 2 段以上下。地表の崖・林床は地上の浸食に任せる)。オーバーワールドだけで動く(仮決め)。 */
    static boolean underground(ServerLevel level, BlockPos pos) {
        return pos.getY() < CaveMouths.groundTop(level, pos.getX(), pos.getZ()) - 1; // 本当の地面(梢をくぐる)より 2 段以上下
    }

    /** テスト用: 元にならない理由(なれば空)。 */
    public static String whyNotSeed(ServerLevel level, BlockPos pos) {
        return ((level.dimension() == Level.OVERWORLD) ? "" : "次元 ") + (loaded(level, pos) ? "" : "読み込まれていない ")
                + (OvergrownLayerData.isCorruptedCell(level, pos, level.getBlockState(pos)) ? "" : "汚染されていない ")
                + (airFacing(level, pos) ? "" : "空気に面していない ") + (underground(level, pos) ? "" : "地面より下でない ");
    }

    /** テスト用: 元を足す(条件を見る)・passes 回ぶん間隔と予算を無視して広げる・待ち行列の大きさ。 */
    public static void seedForTests(ServerLevel level, BlockPos pos) {
        seed(level, pos);
    }

    /** テスト用: 洞窟の壁の 1 マスを這う汚染と同じやり方で汚染する(太い蔦の抽選も走る)。汚染済みになれば true。 */
    public static boolean corruptForTests(ServerLevel level, BlockPos pos, RandomSource random) {
        return corrupt(level, pos, random);
    }

    public static void runForTests(ServerLevel level, int passes) {
        for (int i = 0; i < passes; i++) pass(level, DesignData.sporeField(), false);
    }

    public static int frontierSize(ServerLevel level) {
        return frontier(level).size();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        FRONTIER.clear();
    }
}
