package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.util.ChunkCachedReader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.Arrays;

/**
 * 「{@code center} を中心とした半径 {@code distance} の球の中に汚染ブロック
 * ({@code #overgrown_vegetation:corrupted})が 1 つでもあるか」の判定。
 * {@link com.yayuy.overgrownvegetation.effect.FleeCorruptionGoal} と
 * {@link com.yayuy.overgrownvegetation.phase.Group1FleeCorruptionGoal} が、対象の Mob 1 体につき
 * 毎 tick 呼ぶ(既定の半径 5 で 515 マス)。
 *
 * <h2>速くしている点(結果は同じ)</h2>
 * <ul>
 *     <li>走査順を「中心に近いマスから」に並べ替えてある。答えは「1 つでもあるか」なので順序で結果は
 *     変わらないが、汚染の上・すぐそばに立っている(= 逃げるべき)Mob は最初の数マスで答えが出る。</li>
 *     <li>ブロックの読みは {@link ChunkCachedReader} 経由(毎マスのチャンク検索を省く)。
 *     読み自体は {@code Level#getBlockState} と同じ結果。</li>
 * </ul>
 */
public final class CorruptionProximity {

    /** この半径まではオフセット列を事前計算して使い回す(半径 16 で 33³ の 1/2 ≒ 18000 要素)。 */
    private static final int MAX_PRECOMPUTED_DISTANCE = 16;
    private static final int OFFSET_BIAS = 64;
    private static final int[][] OFFSETS = new int[MAX_PRECOMPUTED_DISTANCE + 1][];

    /**
     * {@code center} を中心とした半径 {@code distance} の球
     * ({@code dx² + dy² + dz² <= distance²})に汚染ブロックが 1 つでもあるか。
     * {@code distance <= 0} なら中心の 1 マスだけを見る。
     */
    public static boolean hasCorruptedWithin(Level level, BlockPos center, int distance) {
        ChunkCachedReader reader = new ChunkCachedReader(level);
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();
        if (distance <= MAX_PRECOMPUTED_DISTANCE) {
            int[] offsets = offsetsFor(Math.max(0, distance));
            for (int packed : offsets) {
                int dx = (packed & 0xFF) - OFFSET_BIAS;
                int dy = ((packed >>> 8) & 0xFF) - OFFSET_BIAS;
                int dz = ((packed >>> 16) & 0xFF) - OFFSET_BIAS;
                if (reader.getBlockState(cx + dx, cy + dy, cz + dz).is(ModTags.CORRUPTED)) {
                    return true;
                }
            }
            return false;
        }
        int distance2 = distance * distance;
        for (int dx = -distance; dx <= distance; dx++) {
            for (int dy = -distance; dy <= distance; dy++) {
                for (int dz = -distance; dz <= distance; dz++) {
                    if (dx * dx + dy * dy + dz * dz > distance2) {
                        continue;
                    }
                    if (reader.getBlockState(cx + dx, cy + dy, cz + dz).is(ModTags.CORRUPTED)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 半径 {@code distance} の球内オフセットを、中心からの距離が近い順に並べた配列(遅延生成)。 */
    private static int[] offsetsFor(int distance) {
        int[] cached = OFFSETS[distance];
        if (cached != null) {
            return cached;
        }
        int distance2 = distance * distance;
        int side = distance * 2 + 1;
        long[] tmp = new long[side * side * side];
        int n = 0;
        for (int dx = -distance; dx <= distance; dx++) {
            for (int dy = -distance; dy <= distance; dy++) {
                for (int dz = -distance; dz <= distance; dz++) {
                    int d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 > distance2) {
                        continue;
                    }
                    int packed = (dx + OFFSET_BIAS) | ((dy + OFFSET_BIAS) << 8) | ((dz + OFFSET_BIAS) << 16);
                    // 上位に距離²、下位にオフセットを入れてソートすると「近い順」になる。
                    tmp[n++] = ((long) d2 << 32) | (packed & 0xFFFFFFFFL);
                }
            }
        }
        long[] sorted = Arrays.copyOf(tmp, n);
        Arrays.sort(sorted);
        int[] result = new int[n];
        for (int i = 0; i < n; i++) {
            result[i] = (int) sorted[i];
        }
        OFFSETS[distance] = result;
        return result;
    }

    private CorruptionProximity() {
    }
}
