package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.data.DesignData;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 森の「縁からの深さ」d と、それで決まる湧きの重み(順 1 の着手順 10、2026-10-03。仕様は {@code docs/ORDER1_IMPL.md} の 7 節)。
 *
 * <p><b>d = そのチャンクから、いちばん近い「過繁茂でないチャンク」までの距離(チャンク)</b>。上限は極相化の半径
 * ({@code climax.json} の {@code biome.neighborRadiusChunks}、12)と<b>同じ 1 つの数</b> —— d が上限に届く = 半径の円の中が
 * 全部過繁茂以上 = 極相化の周りの条件を満たす。距離は極相化と同じく円で測る({@code dx² + dz²})。
 * 過繁茂チャンクの台帳({@link PhaseData#isOvergrownKey})を近い順に引くだけなので、チャンクの読み込みは要らない。
 * 台帳はオーバーワールドのものなので、ほかの次元では重みを掛けない。
 *
 * <p>重み(ユーザー指定。d = 半径の半分でちょうど半々、下限 {@code biome.depthSpawnFloor} 0.15 はユーザー承認):
 * <pre>
 * 縁寄り = 下限 + (1 − 下限) × (1 − d / 半径)     G4・G6・G7・G9(外へ出ていく種)
 * 奥寄り = 下限 + (1 − 下限) × (    d / 半径)     G2・G3(環境生物)・G8(器官を守る)
 * 掛けない                                      G1(汚染されていない所に湧く)・G5(どこでも来る)・G10
 * </pre>
 *
 * <p>湧きのたびに引くので、チャンクごとの d を 1 分だけ覚えておく(台帳はゆっくりしか変わらない)。
 */
public final class OvergrowthDepth {

    /** 帯(どちら寄りに濃くするか)。 */
    public enum Band { EDGE, DEEP, NONE }

    private static final long CACHE_TICKS = 1200L;
    private static final int CACHE_MAX = 4096;

    /** 半径の中のずらし(近い順)。半径が変わったら作り直す。 */
    private static int ringRadius = -1;
    private static int[] ringDx = new int[0];
    private static int[] ringDz = new int[0];
    private static double[] ringDist = new double[0];

    private static final Long2DoubleOpenHashMap DEPTH = new Long2DoubleOpenHashMap();
    private static final Long2LongOpenHashMap MEASURED_AT = new Long2LongOpenHashMap();
    private static MinecraftServer cacheServer;
    private static int cacheRadius = -1;

    /** 上限(= 極相化の半径)。 */
    public static int radius() {
        return DesignData.climax().neighborRadiusChunks();
    }

    /** 群れの帯。 */
    public static Band band(MobGroup group) {
        if (group == null) {
            return Band.NONE;
        }
        return switch (group) {
            case GROUP_4, GROUP_6, GROUP_7, GROUP_9 -> Band.EDGE;
            case GROUP_2, GROUP_3, GROUP_8 -> Band.DEEP;
            default -> Band.NONE;
        };
    }

    /** {@code d} での帯の重み(0〜1)。半径 0 なら 1(重みを掛けない)。 */
    public static double weight(Band band, double d, int radius) {
        if (band == Band.NONE || radius <= 0) {
            return 1.0D;
        }
        double floor = DesignData.climax().depthSpawnFloor();
        double t = Math.max(0.0D, Math.min(1.0D, d / radius));
        return floor + (1.0D - floor) * (band == Band.EDGE ? 1.0D - t : t);
    }

    /**
     * {@code type} を {@code chunk} に湧かせるときの重みの倍率(0〜1)。グループに属さない種・帯の無いグループ・
     * オーバーワールド以外は 1。
     */
    public static double multiplier(ServerLevel level, ChunkPos chunk, EntityType<?> type) {
        Band band = band(MobGroup.of(type));
        if (band == Band.NONE || level.dimension() != Level.OVERWORLD) {
            return 1.0D;
        }
        int r = radius();
        return weight(band, depth(level, chunk), r);
    }

    /** {@code chunk} の d(0〜半径)。オーバーワールドの台帳を引く(どの次元の level を渡してもよい)。 */
    public static double depth(ServerLevel level, ChunkPos chunk) {
        int r = radius();
        long key = chunk.toLong();
        long now = level.getServer().overworld().getGameTime();
        if (cacheServer != level.getServer() || cacheRadius != r || DEPTH.size() > CACHE_MAX) {
            DEPTH.clear();
            MEASURED_AT.clear();
            cacheServer = level.getServer();
            cacheRadius = r;
        }
        if (DEPTH.containsKey(key) && now - MEASURED_AT.get(key) < CACHE_TICKS && now >= MEASURED_AT.get(key)) {
            return DEPTH.get(key);
        }
        double d = measure(PhaseData.get(level), chunk.x, chunk.z, r);
        DEPTH.put(key, d);
        MEASURED_AT.put(key, now);
        return d;
    }

    /** 覚えを使わずに測る(診断用)。 */
    public static double measure(PhaseData data, int chunkX, int chunkZ, int radius) {
        return measure(data::isOvergrownKey, chunkX, chunkZ, radius);
    }

    /**
     * {@code overgrown}(チャンクの {@link ChunkPos#toLong} → 過繁茂以上か)で測る。近い順に見て、最初の「過繁茂でない」
     * チャンクまでの距離。半径の円の中に無ければ半径。GameTest は本物の台帳を書き換えずにこちらで確かめる。
     */
    public static double measure(java.util.function.LongPredicate overgrown, int chunkX, int chunkZ, int radius) {
        ensureRing(radius);
        for (int i = 0; i < ringDx.length; i++) {
            if (!overgrown.test(ChunkPos.asLong(chunkX + ringDx[i], chunkZ + ringDz[i]))) {
                return ringDist[i];
            }
        }
        return radius;
    }

    /** 覚えを捨てる(GameTest で台帳を書き換えた後など)。 */
    public static void clearCache() {
        DEPTH.clear();
        MEASURED_AT.clear();
    }

    private static void ensureRing(int radius) {
        if (radius == ringRadius) {
            return;
        }
        List<int[]> offsets = new ArrayList<>();
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= r2) {
                    offsets.add(new int[]{dx, dz});
                }
            }
        }
        offsets.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        int[] xs = new int[offsets.size()];
        int[] zs = new int[offsets.size()];
        double[] ds = new double[offsets.size()];
        for (int i = 0; i < offsets.size(); i++) {
            xs[i] = offsets.get(i)[0];
            zs[i] = offsets.get(i)[1];
            ds[i] = Math.sqrt(xs[i] * xs[i] + zs[i] * zs[i]);
        }
        ringDx = xs;
        ringDz = zs;
        ringDist = ds;
        ringRadius = radius;
    }

    private OvergrowthDepth() {
    }
}
