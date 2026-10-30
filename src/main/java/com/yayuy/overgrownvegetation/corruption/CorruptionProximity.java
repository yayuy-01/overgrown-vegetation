package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.util.ChunkCachedReader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Arrays;
import java.util.function.Predicate;

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
 *     <li>走査の前に、球にかかるチャンクセクションのパレットを見る({@link #noCorruptedSectionAround})。
 *     どのパレットにも汚染ブロックが載っていなければ、マスを 1 つも読まずに false を返す
 *     (汚染から離れた所にいる Mob は、ここで 515 マスの読みがまるごと要らなくなる)。
 *     一部のセクションにだけ載っているときは、載っていないセクションのマスを読まずに飛ばす
 *     ({@link #sectionMask}、2026-09-24)。</li>
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
            int r = Math.max(0, distance);
            // 球にかかるセクション(最大 3×3×3)ごとに「汚染ブロックがありうるか」をパレットで先に見る
            // ({@link #sectionMask})。どこにも無ければ 1 マスも読まない(以前と同じ)。ありうるセクションが一部だけなら、
            // 無いと分かっているセクションのマスは読まずに飛ばす(2026-09-24 軽量化、報告 534)。
            // 汚染域の縁から数マスの所にいる Mob は、以前は球の 515 マスを全部読んでいた。
            // 答えは「1 つでもあるか」で、飛ばすのは汚染ブロックが出てこないと分かっているマスだけなので結果は同じ。
            // 同じ近い順に読むので、最初に見つかるマスも、その手前で同期ロードする未ロードのチャンクも変わらない
            // (未ロードのチャンクのセクションは「ありうる」側に入れてある)。
            long mask = sectionMask(level, reader, cx, cy, cz, r);
            if (mask == 0L) {
                return false;
            }
            int minChunkX = (cx - r) >> 4;
            int minChunkZ = (cz - r) >> 4;
            int minSectionY = (cy - r) >> 4;
            int[] offsets = offsetsFor(r);
            for (int packed : offsets) {
                int x = cx + (packed & 0xFF) - OFFSET_BIAS;
                int y = cy + ((packed >>> 8) & 0xFF) - OFFSET_BIAS;
                int z = cz + ((packed >>> 16) & 0xFF) - OFFSET_BIAS;
                if ((mask & sectionBit(x >> 4, y >> 4, z >> 4, minChunkX, minSectionY, minChunkZ)) == 0L) {
                    continue;
                }
                if (reader.getBlockState(x, y, z).is(ModTags.CORRUPTED)) {
                    return true;
                }
            }
            return false;
        }
        if (noCorruptedSectionAround(level, reader, cx, cy, cz, distance)) {
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

    /** パレットの状態が汚染ブロックか(マスごとの判定と同じタグ)。 */
    private static final Predicate<BlockState> IS_CORRUPTED = state -> state.is(ModTags.CORRUPTED);

    /**
     * 半径 {@code r} の球を囲む直方体にかかるチャンクセクションの<b>どれにも汚染ブロックがありえない</b>か。
     * true なら球のどのマスを読んでも汚染ブロックは出てこない(= 走査の答えは必ず false)。
     *
     * <p>パレット({@link LevelChunkSection#maybeHas})はそのセクションに置かれうる状態の上位集合
     * (もう使われていない状態が残っていることはあっても、置かれている状態が漏れることはない。
     * 全状態パレットなら常に「ありうる」)なので、載っていなければ本当に無い。
     * 次のときは確かめずに false を返し、いつもの走査に任せる:
     * <ul>
     *     <li>チャンクがまだロードされていない —— ここでは読み込まない({@link ChunkCachedReader#chunkNow})。
     *     走査の方はこれまでどおり {@code Level#getBlockState} と同じく同期ロードする。</li>
     *     <li>デバッグワールド —— ブロックがセクションではなく座標から決まる。</li>
     *     <li>{@code AIR} / {@code VOID_AIR} 自体が汚染タグに入っている —— 空気だけのセクションは
     *     {@code LevelChunk#getBlockState} が {@code AIR} を、高さ範囲外は {@code VOID_AIR} を返すので、
     *     パレットを見ても答えにならない。</li>
     * </ul>
     */
    private static boolean noCorruptedSectionAround(Level level, ChunkCachedReader reader,
                                                    int cx, int cy, int cz, int r) {
        if (!paletteAnswers(level)) {
            return false;
        }
        // 高さ範囲外のマスは VOID_AIR(汚染ではない)なので、範囲内のセクションだけ見ればよい。
        int minSectionY = Math.max(level.getMinSection(), (cy - r) >> 4);
        int maxSectionY = Math.min(level.getMaxSection() - 1, (cy + r) >> 4);
        if (minSectionY > maxSectionY) {
            return true;
        }
        for (int chunkX = (cx - r) >> 4; chunkX <= (cx + r) >> 4; chunkX++) {
            for (int chunkZ = (cz - r) >> 4; chunkZ <= (cz + r) >> 4; chunkZ++) {
                LevelChunk chunk = reader.chunkNow(chunkX << 4, chunkZ << 4);
                if (chunk == null) {
                    return false;
                }
                for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                    LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
                    // 空気だけのセクションは読むと AIR(上で汚染でないと確かめた)なのでパレットは見ない。
                    if (!section.hasOnlyAir() && section.maybeHas(IS_CORRUPTED)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * パレットを見れば「汚染ブロックがあるか」が分かるワールドか({@link #noCorruptedSectionAround} の前提)。
     * デバッグワールドと、{@code AIR} / {@code VOID_AIR} 自体が汚染タグに入っているときは分からない。
     */
    private static boolean paletteAnswers(Level level) {
        return !level.isDebug()
                && !Blocks.AIR.defaultBlockState().is(ModTags.CORRUPTED)
                && !Blocks.VOID_AIR.defaultBlockState().is(ModTags.CORRUPTED);
    }

    /**
     * 半径 {@code r}(0〜{@link #MAX_PRECOMPUTED_DISTANCE})の球を囲む直方体にかかるセクション(最大 3×3×3)のうち、
     * <b>汚染ブロックが出てくるかもしれない</b>ものをビットで返す(2026-09-24 軽量化、報告 534)。
     * ビットの位置は {@link #sectionBit}。0 なら球のどのマスを読んでも汚染ブロックは出てこない。
     *
     * <p>判定の中身は {@link #noCorruptedSectionAround} と同じで、それをセクションごとに分けたもの。
     * 「かもしれない」に倒すのは: パレットに汚染ブロックが載っているセクション、読み込まれていないチャンクの
     * セクション(ここでは読み込まない。そのマスはこれまでどおり読んで同期ロードする)。パレットで答えが出ない
     * ワールド({@link #paletteAnswers})では全部のビットを立てる。空気だけのセクションと高さ範囲外
     * (読むと AIR / VOID_AIR で、どちらも汚染でないことを確かめてある)はビットを立てない。
     */
    private static long sectionMask(Level level, ChunkCachedReader reader, int cx, int cy, int cz, int r) {
        if (!paletteAnswers(level)) {
            return -1L;
        }
        int minChunkX = (cx - r) >> 4;
        int maxChunkX = (cx + r) >> 4;
        int minChunkZ = (cz - r) >> 4;
        int maxChunkZ = (cz + r) >> 4;
        int minSectionY = (cy - r) >> 4;
        int lowSectionY = Math.max(level.getMinSection(), minSectionY);
        int highSectionY = Math.min(level.getMaxSection() - 1, (cy + r) >> 4);
        long mask = 0L;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                LevelChunk chunk = reader.chunkNow(chunkX << 4, chunkZ << 4);
                for (int sy = lowSectionY; sy <= highSectionY; sy++) {
                    if (chunk != null) {
                        LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
                        if (section.hasOnlyAir() || !section.maybeHas(IS_CORRUPTED)) {
                            continue;
                        }
                    }
                    mask |= sectionBit(chunkX, sy, chunkZ, minChunkX, minSectionY, minChunkZ);
                }
            }
        }
        return mask;
    }

    /**
     * {@link #sectionMask} のビット。チャンク X・Z とセクション Y の、直方体の最小からの差(どれも 0〜2)で決まる
     * (半径 16 以下の球はどの向きにも 33 マス以内 = 3 セクション以内に収まる)。
     */
    private static long sectionBit(int chunkX, int sectionY, int chunkZ, int minChunkX, int minSectionY, int minChunkZ) {
        return 1L << (((chunkX - minChunkX) * 3 + (chunkZ - minChunkZ)) * 3 + (sectionY - minSectionY));
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

    /**
     * {@code center} から水平に {@code radius} ブロックの四角にかかる<b>読み込み済みの</b>チャンクに、繁茂系でない
     * ({@link ModBiomes#isOvergrownLike} が false の)チャンクが 1 つでもあるか(2026-10-06、ユーザー指定)。
     *
     * <p>汚染から逃げる 2 つの Goal は、逃げ先の候補をこの四角の中から探し、繁茂系のバイオームの上の候補は捨てる。
     * 四角にかかるチャンクがどれも繁茂系なら探しても必ず失敗し、「とにかく今の場所から離れる」ランダムな方向へ歩くだけになる
     * (外へ向かう動きではない)。過繁茂域の奥では毎回これをやり直していて、平常時のサーバースレッドの 13% だった。
     * 見るのはチャンクごとに 1 点(チャンクの中心を四角の中へ寄せた点、高さは {@code center})の生のバイオーム(4×4×4 の格子)。
     * 読み込まれていないチャンクは見ない(読みに行くと読み込みを待って止まる。どのみち経路も引けない)。
     */
    public static boolean hasOpenChunkWithin(ServerLevel level, BlockPos center, int radius) {
        int y = Mth.clamp(center.getY(), level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        int minX = center.getX() - radius;
        int maxX = center.getX() + radius;
        int minZ = center.getZ() - radius;
        int maxZ = center.getZ() + radius;
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                int x = Mth.clamp((cx << 4) + 8, minX, maxX);
                int z = Mth.clamp((cz << 4) + 8, minZ, maxZ);
                Holder<Biome> biome = chunk.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
                if (!ModBiomes.isOvergrownLike(biome)) {
                    return true;
                }
            }
        }
        return false;
    }

    private CorruptionProximity() {
    }
}
