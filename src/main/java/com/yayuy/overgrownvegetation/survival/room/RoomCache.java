package com.yayuy.overgrownvegetation.survival.room;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.RoomSpec;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * 部屋の判定の覚え(2026-11-13 段 2a)。プレイヤー・Mob の暴露量の読み(20〜40 tick ごと)で毎回たどらないように、
 * たどったマス 1 つ 1 つに結果を結びつけて覚える(部屋の中のどこから聞かれても 1 回のたどりで済む。部屋でなかった結果も同じ)。
 *
 * <p>捨て方: ブロックが変わったら({@code ServerLevelRoomInvalidateMixin} → {@link #onBlockChanged})、そのマスと 6 方の隣に結びついた結果を捨てる
 * (中のマスか、その隣 = 境界)。空気の通り方の印({@link AirCells})が変わらない変化(草の成長・汚染で石が別の石に…)は無視する。
 * 遠くで空をふさいだ・上限の先をふさいだのは見えないので、部屋でなかった結果は短め({@link RoomSpec#openTtlTicks()})に忘れる。
 * {@link ServerStoppedEvent} で空にする。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class RoomCache {

    private record Entry(RoomScan scan, long expires) {
    }

    private static final class Dim {
        final Long2ObjectOpenHashMap<Entry> byCell = new Long2ObjectOpenHashMap<>();
        long tick = Long.MIN_VALUE;
        int searchesThisTick;
    }

    private static final Map<ResourceKey<Level>, Dim> DIMS = new HashMap<>();

    /** 測り(開発用のコマンド・GameTest): 覚えから答えた回数・たどった回数・たどりにかかった合計 ns・上限で断った回数・捨てた結果の数。 */
    public static long hits, scans, scanNanos, refused, invalidated;
    /** GameTest: 1 tick にたどる回数の上限を外す(同じ tick に何度も確かめるため)。 */
    public static boolean unlimitedForTests;

    private RoomCache() {
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
    }

    public static void clear() {
        DIMS.clear();
        hits = scans = scanNanos = refused = invalidated = 0;
    }

    /** 覚えているマスの数(開発用)。 */
    public static int cachedCells(ServerLevel level) {
        Dim d = DIMS.get(level.dimension());
        return d == null ? 0 : d.byCell.size();
    }

    /**
     * {@code pos}(足元でも頭でもよい)が閉じた部屋の中なら、その結果。部屋でない・判定を切ってある・この tick にたどる上限を超えた → null。
     */
    public static RoomScan roomAt(ServerLevel level, BlockPos pos) {
        RoomScan r = scanAt(level, pos);
        return r != null && r.isRoom() ? r : null;
    }

    /** {@code pos} の結果(覚えから。無ければたどる)。判定を切ってある・上限で断った → null。 */
    public static RoomScan scanAt(ServerLevel level, BlockPos pos) {
        RoomSpec spec = DesignData.sporeField().room();
        if (!spec.enabled()) return null;
        LevelWorld w = new LevelWorld(level);
        BlockPos start = RoomFinder.start(w, pos);
        if (start == null) return null;
        // 外に立っている(いちばん多い)なら覚えを引かずに答える
        if (w.openSky(start.getX(), start.getY(), start.getZ())) return RoomScan.simple(RoomScan.Status.OPEN, start);
        Dim d = DIMS.computeIfAbsent(level.dimension(), k -> new Dim());
        long now = level.getGameTime();
        long key = start.asLong();
        Entry e = d.byCell.get(key);
        if (e != null && now < e.expires && now >= e.expires - Math.max(spec.roomTtlTicks(), spec.openTtlTicks())) {
            hits++;
            return e.scan;
        }
        if (d.tick != now) {
            d.tick = now;
            d.searchesThisTick = 0;
        }
        if (d.searchesThisTick >= spec.maxSearchesPerTick() && !unlimitedForTests) {
            // この tick はもうたどらない: 期限の切れた結果があればそれを使う(ブロックの変化で捨てた物は残っていない)。無ければ「部屋でない」
            refused++;
            return e != null ? e.scan : null;
        }
        if (e != null) forget(d, e);
        d.searchesThisTick++;
        long t0 = System.nanoTime();
        RoomScan scan = RoomFinder.scan(w, start, spec, false);
        scanNanos += System.nanoTime() - t0;
        scans++;
        if (scan.status() == RoomScan.Status.UNLOADED || scan.cells().isEmpty()) return scan;
        if (d.byCell.size() + scan.cells().size() > spec.maxCachedCells()) d.byCell.clear();
        Entry ne = new Entry(scan, now + (scan.isRoom() ? spec.roomTtlTicks() : spec.openTtlTicks()));
        for (LongIterator it = scan.cells().iterator(); it.hasNext(); ) d.byCell.put(it.nextLong(), ne);
        return scan;
    }

    /** 覚えを使わずに全部のすき間を集めてたどる(開発用のコマンド)。 */
    public static RoomScan scanFull(ServerLevel level, BlockPos pos) {
        LevelWorld w = new LevelWorld(level);
        BlockPos start = RoomFinder.start(w, pos);
        if (start == null) return RoomScan.simple(RoomScan.Status.SOLID, pos);
        return RoomFinder.scan(w, start, DesignData.sporeField().room(), true);
    }

    /** ブロックが変わった(サーバーの {@code onBlockStateChange})。 */
    public static void onBlockChanged(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (DIMS.isEmpty()) return;
        Dim d = DIMS.get(level.dimension());
        if (d == null || d.byCell.isEmpty()) return;
        if (oldState == newState || AirCells.of(oldState) == AirCells.of(newState)) return;
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        drop(d, BlockPos.asLong(x, y, z));
        drop(d, BlockPos.asLong(x + 1, y, z));
        drop(d, BlockPos.asLong(x - 1, y, z));
        drop(d, BlockPos.asLong(x, y + 1, z));
        drop(d, BlockPos.asLong(x, y - 1, z));
        drop(d, BlockPos.asLong(x, y, z + 1));
        drop(d, BlockPos.asLong(x, y, z - 1));
    }

    private static void drop(Dim d, long cell) {
        Entry e = d.byCell.get(cell);
        if (e != null) forget(d, e);
    }

    private static void forget(Dim d, Entry e) {
        invalidated++;
        for (LongIterator it = e.scan.cells().iterator(); it.hasNext(); ) {
            long c = it.nextLong();
            if (d.byCell.get(c) == e) d.byCell.remove(c);
        }
    }

    /** サーバーの世界の読み口。チャンクを読み込ませない(読み込まれていなければ {@link AirCells#UNLOADED})。 */
    static final class LevelWorld implements RoomFinder.World {
        private final ServerLevel level;
        private final int minY, maxY;
        private long lastKey = Long.MIN_VALUE;
        private LevelChunk last;

        LevelWorld(ServerLevel level) {
            this.level = level;
            this.minY = level.getMinBuildHeight();
            this.maxY = level.getMaxBuildHeight();
        }

        private LevelChunk chunk(int x, int z) {
            int cx = x >> 4, cz = z >> 4;
            long k = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
            if (k != lastKey) {
                lastKey = k;
                last = level.getChunkSource().getChunkNow(cx, cz);
            }
            return last;
        }

        @Override
        public byte cell(int x, int y, int z) {
            if (y >= maxY) return AirCells.OUTSIDE;
            if (y < minY) return AirCells.SOLID | AirCells.COLLIDES | AirCells.NATURAL;
            LevelChunk c = chunk(x, z);
            if (c == null) return AirCells.UNLOADED;
            return AirCells.of(c.getBlockState(new BlockPos(x, y, z)));
        }

        @Override
        public boolean openSky(int x, int y, int z) {
            if (y >= maxY) return true;
            LevelChunk c = chunk(x, z);
            if (c == null) return false;
            // 葉は通す(AirCells)ので、葉を除いた高さ地図より上は空が開けている
            return y >= c.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
        }
    }
}
