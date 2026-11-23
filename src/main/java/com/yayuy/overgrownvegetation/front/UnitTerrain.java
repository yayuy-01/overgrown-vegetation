package com.yayuy.overgrownvegetation.front;

import com.yayuy.overgrownvegetation.data.DesignData;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;

import javax.annotation.Nullable;

/**
 * 部隊が地図の上で見る地形(2026-11-21 前線と部隊 4「地図の上の動き」)。読み込まれていない所でも動くので、ブロックは見ず、
 * 世界の生成のバイオーム(チャンクを読み込まずに引ける)だけで決める。チャンクごとに覚える(重さ)。
 * <ul>
 *   <li>水の升(海・川)は避けて回り道する。深い海は流れ木もどきで渡る(区切り 3。今は避けるだけ)。</li>
 *   <li>山の升は半分の速さ。</li>
 * </ul>
 */
public final class UnitTerrain {

    public static final byte LAND = 0, WATER = 1, DEEP = 2, MOUNTAIN = 3;

    /** チャンク → 地形(覚えておく数の上限を超えたら全部忘れる)。 */
    private static final Long2ByteOpenHashMap CACHE = new Long2ByteOpenHashMap();
    /** 11-23 サーバーの tick の軽量化: 20 万だと部隊が広がった F7 で毎分のように溢れて全部忘れ、バイオームを引き直していた(答えは同じ。約 20 MB)。 */
    private static final int CACHE_MAX = 1_000_000;
    static {
        CACHE.defaultReturnValue((byte) -1);
    }
    /** テスト用: 地形の決め打ち(null で本物)。 */
    @Nullable
    public static java.util.function.ToIntBiFunction<Integer, Integer> terrainForTests;

    private UnitTerrain() {
    }

    public static void clearCache() {
        CACHE.clear();
    }

    /** ブロック座標 (x, z) のチャンクの地形。 */
    public static byte kind(ServerLevel level, double x, double z) {
        int cx = Mth.floor(x) >> 4, cz = Mth.floor(z) >> 4;
        if (terrainForTests != null) return (byte) terrainForTests.applyAsInt(cx, cz);
        long key = ChunkPos.asLong(cx, cz);
        byte hit = CACHE.get(key); // 無ければ -1(引くのを 1 回に。11-23 サーバーの tick の軽量化)
        if (hit >= 0) return hit;
        byte k = sample(level, cx, cz);
        if (CACHE.size() >= CACHE_MAX) CACHE.clear();
        CACHE.put(key, k);
        return k;
    }

    private static byte sample(ServerLevel level, int cx, int cz) {
        int bx = (cx << 4) + 8, bz = (cz << 4) + 8;
        Holder<Biome> b = level.getUncachedNoiseBiome(QuartPos.fromBlock(bx), QuartPos.fromBlock(level.getSeaLevel()), QuartPos.fromBlock(bz));
        if (b.is(BiomeTags.IS_DEEP_OCEAN)) return DEEP;
        if (b.is(BiomeTags.IS_OCEAN) || b.is(BiomeTags.IS_RIVER)) return WATER;
        if (b.is(BiomeTags.IS_MOUNTAIN)) return MOUNTAIN;
        return LAND;
    }

    /** 進めない地形か(水・深い海)。 */
    public static boolean blocked(byte k) {
        return k == WATER || k == DEEP;
    }

    /**
     * 次の 1 歩の向きと速さの倍率 {dirX, dirZ, factor}。まっすぐが水なら ±45°・±90°・±135° の順に陸を探す(回り道)。どれも水なら null(待つ)。
     * 今いる所が水(海の真ん中に出された)なら、まっすぐ進む(陸に上がるまで)。
     */
    @Nullable
    public static double[] stepDir(ServerLevel level, double x, double z, double dx, double dz, double step) {
        double look = Math.max(16.0D, step);
        if (blocked(kind(level, x, z))) return new double[]{dx, dz, 1.0D};
        for (int i = 0; i < ANGLES.length; i++) {
            double a = ANGLES[i];
            double c = Math.cos(a), s = Math.sin(a);
            double rx = dx * c - dz * s, rz = dx * s + dz * c;
            byte k = kind(level, x + rx * look, z + rz * look);
            if (blocked(k)) continue;
            double f = k == MOUNTAIN ? DesignData.front().mountainSpeedFactor : 1.0D;
            return new double[]{rx, rz, f};
        }
        return null;
    }

    private static final double[] ANGLES = {0.0D, Math.PI / 4, -Math.PI / 4, Math.PI / 2, -Math.PI / 2, 3 * Math.PI / 4, -3 * Math.PI / 4};
}
