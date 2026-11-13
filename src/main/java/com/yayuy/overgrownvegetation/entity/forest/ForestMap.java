package com.yayuy.overgrownvegetation.entity.forest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 森の地図(11-08、森の中を動く型 ② の段 2。① の全面に張り付く型と共通に使う予定の最初の一部)。
 *
 * <p>今あるのは<b>「一番近い幹(障害物)までの距離」</b>だけ。歩く高さ {@code y}(足元)ごとに、16 × 16 列の区画で持つ。
 * 障害物 = 足元の 1 段上に当たりのあるブロックがある列(幹・壁・茂み・2 段以上の崖。1 段の段差は歩いて上がるので数えない)。
 * 11-08 夜: 前は 2 段上も見ていたが、頭の高さにかかる葉・枝を「幹」と数え、低い葉の下で狭い所の構え(頭を上げる)になったので 1 段上だけにした
 * (頭の上の葉・枝は首を下げる構えが見る)。
 *
 * <p>重くしない作り: 区画を初めて聞かれたとき、メインスレッドで周り {@link #APRON} 列を含む障害物の有無だけを写し(約 1,600 回のブロック読み)、
 * 距離の計算は裏のスレッド 1 本で行う。できるまでは「分からない」({@link Float#NaN})を返す(呼ぶ側は重みを足さない)。
 * {@link #EXPIRE_TICKS} たった区画は次に聞かれたとき作り直す(作り直しの間は古い値を返す)。ブロックの変化は追わない。
 */
public final class ForestMap {

    /** 区画の一辺(列)。 */
    static final int TILE = 16;
    /** 距離を見る上限(ブロック)= 区画の外へ写す幅【仮】。これより遠い幹は「遠い」(= APRON)。 */
    public static final int APRON = 6;
    /** 区画を作り直すまでの tick【仮】(20 秒)。 */
    static final long EXPIRE_TICKS = 400L;
    /** これより古い区画は捨てる(tick)【仮】。 */
    static final long DROP_TICKS = 2400L;

    private static final Map<ServerLevel, ForestMap> MAPS = new WeakHashMap<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "OV-ForestMap");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /** 区画: 列ごとの距離(1/4 ブロック単位、0〜APRON×4)。 */
    private record Tile(byte[] dist, long builtAt) {
    }

    private final ServerLevel level;
    private final ConcurrentHashMap<Long, Tile> tiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> pending = new ConcurrentHashMap<>();
    private long lastSweep;
    /** 計測用: 写しにかかった時間(ns)と回数。 */
    public static volatile long snapshotNanos, snapshotCount, buildNanos;

    private ForestMap(ServerLevel level) {
        this.level = level;
    }

    public static synchronized ForestMap of(ServerLevel level) {
        return MAPS.computeIfAbsent(level, ForestMap::new);
    }

    private static long key(int tx, int y, int tz) {
        return ((long) (tx & 0x3FFFFF) << 42) | ((long) (tz & 0x3FFFFF) << 20) | (y & 0xFFFFF);
    }

    /**
     * 列 (x, z) で足元 y に立ったとき、一番近い障害物の列までの距離(ブロック、列の中心どうし − 0.5)。上限は {@link #APRON}。
     * 区画がまだできていなければ作るのを頼み、{@link Float#NaN} を返す。メインスレッドから呼ぶ。
     */
    public float clearance(int x, int y, int z) {
        int tx = Math.floorDiv(x, TILE), tz = Math.floorDiv(z, TILE);
        long k = key(tx, y, tz);
        Tile t = this.tiles.get(k);
        long now = this.level.getGameTime();
        if (t == null || now - t.builtAt() > EXPIRE_TICKS) {
            request(tx, y, tz, k);
        }
        sweep(now);
        if (t == null) return Float.NaN;
        int lx = x - tx * TILE, lz = z - tz * TILE;
        return (t.dist()[lx + lz * TILE] & 0xFF) / 4.0F;
    }

    /** 区画を頼んである(作り中)か。GameTest 用。 */
    public boolean busy() {
        return !this.pending.isEmpty();
    }

    private void request(int tx, int y, int tz, long k) {
        if (this.pending.putIfAbsent(k, Boolean.TRUE) != null) return;
        long t0 = System.nanoTime();
        // メインスレッドで障害物の有無だけを写す
        int n = TILE + APRON * 2;
        boolean[] obstacle = new boolean[n * n];
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int x0 = tx * TILE - APRON, z0 = tz * TILE - APRON;
        for (int dz = 0; dz < n; dz++) {
            for (int dx = 0; dx < n; dx++) {
                int x = x0 + dx, z = z0 + dz;
                if (!this.level.hasChunk(x >> 4, z >> 4)) continue;
                obstacle[dx + dz * n] = blocks(p.set(x, y + 1, z));
            }
        }
        snapshotNanos += System.nanoTime() - t0;
        snapshotCount++;
        long built = this.level.getGameTime();
        WORKER.execute(() -> {
            try {
                long b0 = System.nanoTime();
                byte[] dist = distances(obstacle, n);
                buildNanos += System.nanoTime() - b0;
                this.tiles.put(k, new Tile(dist, built));
            } catch (Throwable e) {
                OvergrownVegetation.LOGGER.error("森の地図の区画を作れない", e);
            } finally {
                this.pending.remove(k);
            }
        });
    }

    private boolean blocks(BlockPos p) {
        BlockState s = this.level.getBlockState(p);
        // 見えない壁(バリア)は幹ではないので数えない(GameTest のテスト場の壁で、壁の近くの個体が「狭い所」と見ていた、11-08 夜)
        return !s.isAir() && !s.is(net.minecraft.world.level.block.Blocks.BARRIER) && !s.getCollisionShape(this.level, p).isEmpty();
    }

    /** 裏のスレッド: 区画の列ごとに、APRON 以内の一番近い障害物の列までの距離。 */
    static byte[] distances(boolean[] obstacle, int n) {
        byte[] out = new byte[TILE * TILE];
        for (int lz = 0; lz < TILE; lz++) {
            for (int lx = 0; lx < TILE; lx++) {
                int cx = lx + APRON, cz = lz + APRON;
                double best = APRON;
                for (int dz = -APRON; dz <= APRON; dz++) {
                    for (int dx = -APRON; dx <= APRON; dx++) {
                        if (!obstacle[(cx + dx) + (cz + dz) * n]) continue;
                        double d = Math.max(0.0D, Math.sqrt(dx * dx + dz * dz) - 0.5D);
                        if (d < best) best = d;
                    }
                }
                out[lx + lz * TILE] = (byte) Math.round(best * 4.0D);
            }
        }
        return out;
    }

    private void sweep(long now) {
        if (now - this.lastSweep < 200L) return;
        this.lastSweep = now;
        this.tiles.entrySet().removeIf(e -> now - e.getValue().builtAt() > DROP_TICKS);
    }
}
