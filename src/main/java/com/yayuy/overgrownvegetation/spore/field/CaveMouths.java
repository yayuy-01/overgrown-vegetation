package com.yayuy.overgrownvegetation.spore.field;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import com.yayuy.overgrownvegetation.port.event.tick.LevelTickEvent;

/**
 * 洞窟の口の多さを測る(2026-11-04 暴露量ノート 追記 6【決定】「洞窟の口の多さ = 読み込んだチャンクで測る」)。
 *
 * <p>測り方(仮決め): チャンクの 256 列それぞれで、<b>まわりの地面より下</b>(東西南北それぞれ 1〜3 列先のいちばん高い地面のうち、
 * 4 方でいちばん低いもの。チャンクの外の向きは見ない)で、空の光が 1 以上差す空気を {@code underground.mouthScanDepth} ブロックの深さまで数え、
 * {@code underground.mouthFullCells} 個で 1 になるよう 0〜1 に。列の高さ地図は穴の真下では床そのものになる(メモリ「洞窟の口は高さ地図で探せない」)ので
 * まわりの列から地面を決める。こうすると縦穴・天井の穴・崖の横穴・掘った階段は数え、斜面・森の林床・家の中は数えない。
 *
 * <p>読み込まれたチャンクは {@code loadDelayTicks} 後に(光が揃うのを待って)測り、プレイヤーのまわり {@code remeasureRadius} チャンクは
 * {@code remeasureTicks} ごとに 1 チャンクずつ測り直す(掘った穴も口になる)。どちらも {@link TickBudget} の予算の中で。
 * 答えは {@link SporeFieldServer#onMouthMeasured} で場へ(区画が無ければ捨てられる。作り直すときは既定値)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class CaveMouths {

    /** 読み込まれたチャンク → 測ってよい時刻(ゲーム時刻)。読み込まれた順 = 時刻の順。 */
    private static final Long2LongLinkedOpenHashMap PENDING = new Long2LongLinkedOpenHashMap();
    /** 測る順番の来たチャンク。 */
    private static final LongLinkedOpenHashSet READY = new LongLinkedOpenHashSet();
    /** 測り直しの螺旋の何番目か。 */
    private static int spiral;
    private static int[][] spiralOffsets = new int[0][];
    private static int spiralRadius = -1;
    /** 直近に測った数と値(/ov perf spore の表示・テスト)。 */
    private static volatile int measured;

    private CaveMouths() {
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!(event.getChunk() instanceof LevelChunk chunk) || !DesignData.sporeField().enabled()) return;
        long key = chunk.getPos().toLong();
        if (!PENDING.containsKey(key) && !READY.contains(key)) {
            PENDING.put(key, level.getGameTime() + DesignData.sporeField().mouthLoadDelayTicks());
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        SporeFieldTable t = DesignData.sporeField();
        if (!t.enabled()) return;
        long now = level.getGameTime();
        // 待ちの済んだチャンクを順番へ
        while (!PENDING.isEmpty() && PENDING.get(PENDING.firstLongKey()) <= now) {
            READY.add(PENDING.firstLongKey());
            PENDING.removeFirstLong();
        }
        // プレイヤーのまわりをゆっくり測り直す
        if (t.mouthRemeasureTicks() > 0 && now % t.mouthRemeasureTicks() == 0 && !level.players().isEmpty()) {
            int[][] offs = offsets(t.mouthRemeasureRadius());
            int[] o = offs[Math.floorMod(spiral++, offs.length)];
            for (ServerPlayer p : level.players()) {
                READY.add(ChunkPos.asLong((p.getBlockX() >> 4) + o[0], (p.getBlockZ() >> 4) + o[1]));
            }
        }
        if (READY.isEmpty() || !TickBudget.mayStart(PerfJob.SPORE_CAVE_MOUTHS)) return;
        long start = TickBudget.begin();
        int done = 0;
        try {
            while (!READY.isEmpty() && (done == 0 || TickBudget.timeLeft())) {
                long key = READY.removeFirstLong();
                int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue; // もう読み込まれていない(次に読み込まれたら測る)
                SporeFieldServer.onMouthMeasured(cx, cz, measure(level, chunk, t.mouthScanDepth(), t.mouthFullCells()));
                done++;
            }
        } finally {
            TickBudget.end(PerfJob.SPORE_CAVE_MOUTHS, start, done);
        }
        measured += done;
    }

    private static int[][] offsets(int radius) {
        if (radius != spiralRadius) {
            java.util.List<int[]> list = new java.util.ArrayList<>();
            for (int dz = -radius; dz <= radius; dz++)
                for (int dx = -radius; dx <= radius; dx++) if (dx * dx + dz * dz <= radius * radius) list.add(new int[]{dx, dz});
            list.sort(java.util.Comparator.comparingInt(a -> a[0] * a[0] + a[1] * a[1]));
            spiralOffsets = list.toArray(new int[0][]);
            spiralRadius = radius;
        }
        return spiralOffsets;
    }

    /** チャンクの洞窟の口の多さ(0〜1)。サーバースレッド、チャンクが読み込まれていること。 */
    public static float measure(ServerLevel level, LevelChunk chunk, int depth, int fullCells) {
        return Math.min(1f, countMouthCells(level, chunk, depth) / (float) Math.max(1, fullCells));
    }

    /** 地面より下で空の光が差す空気の数(上の説明の数え方)。 */
    public static int countMouthCells(ServerLevel level, LevelChunk chunk, int depth) {
        int[] top = new int[256];
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                // チャンクの getHeight はいちばん上のブロックの y(-1 済み。メモリ「チャンクの getHeight は -1 済み」)。梢はくぐって本当の地面へ
                int x = chunk.getPos().getMinBlockX() + lx, z = chunk.getPos().getMinBlockZ() + lz;
                top[(lz << 4) | lx] = groundTop(chunk, x, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz), z, level.getMinBuildHeight());
            }
        int minY = level.getMinBuildHeight();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int count = 0;
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                int ground = groundAround(top, lx, lz);
                int lo = Math.max(minY, ground - depth);
                for (int y = ground; y > lo; y--) { // ground = まわりの地面のいちばん上のブロック。数えるのはその高さ以下の空気
                    m.set(x0 + lx, y, z0 + lz);
                    if (!chunk.getBlockState(m).isAir()) continue;
                    if (level.getBrightness(LightLayer.SKY, m) > 0) count++;
                }
            }
        return count;
    }

    /**
     * 列 (lx, lz) のまわりの地面の高さ: 東西南北それぞれ 1〜3 列先(チャンクの中だけ)のいちばん高い地面のうち、4 方でいちばん低いもの。
     * どの向きもチャンクの外なら自分の列の高さ(穴の真下では床になるので、まわりの列で決める)。
     */
    static int groundAround(int[] top, int lx, int lz) {
        int best = Integer.MAX_VALUE;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int hi = Integer.MIN_VALUE;
            for (int s = 1; s <= 3; s++) {
                int x = lx + d[0] * s, z = lz + d[1] * s;
                if (x < 0 || x > 15 || z < 0 || z > 15) break;
                hi = Math.max(hi, top[(z << 4) | x]);
            }
            if (hi != Integer.MIN_VALUE) best = Math.min(best, hi);
        }
        return best == Integer.MAX_VALUE ? top[(lz << 4) | lx] : best;
    }

    /**
     * 本当の地面のいちばん上のブロックの y(2026-11-04): 列の頂 {@code topY}(葉を除いた高さ地図。過繁茂の葉・幹は高さ地図に入るので梢のことがある)から、
     * 木の一部({@link com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder#isTreePart})・空気・動きを止めない植物をくぐった最初のブロック。
     * 林冠の下の空気を「地面より下」と見ないために、洞窟の口の測り・地下の読み・壁を這う汚染が使う。見つからなければ topY。
     */
    public static int groundTop(net.minecraft.world.level.BlockGetter level, int x, int topY, int z, int minY) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, topY, z);
        for (int d = 0; d < 200 && m.getY() > minY; d++, m.move(0, -1, 0)) {
            net.minecraft.world.level.block.state.BlockState s = level.getBlockState(m);
            if (com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder.isTreePart(s) || s.isAir() || !s.blocksMotion()) continue;
            return m.getY();
        }
        return topY;
    }

    /** 列 (x, z) の本当の地面のいちばん上の y(Level の高さ地図から)。 */
    public static int groundTop(net.minecraft.world.level.Level level, int x, int z) {
        return groundTop(level, x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z, level.getMinBuildHeight());
    }

    /** 今まで測った数(/ov の表示)。 */
    public static int measuredCount() {
        return measured;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
        READY.clear();
        measured = 0;
    }
}
