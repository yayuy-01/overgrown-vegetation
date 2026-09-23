package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.corruption.LeyVeinField;
import net.minecraft.world.level.ChunkPos;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>器官がどこに生えうるか</b>を決める場(2026-09-23)。
 *
 * <h2>地脈の上に立つ</h2>
 * <p>器官は<b>地脈に沿って</b>生える(要望)。地脈は極相の地表を走る網目
 * ({@link LeyVeinField}) —— 世界に 1 本の筋として繋がっているので、
 * 「器官を探す」が<b>地脈を辿る</b>という形になる。
 *
 * <h2>確率で撒かない</h2>
 * <p>世界をひと辺 {@code cellSizeChunks} チャンクの升目に切り、
 * <b>升目の中で地脈が通っているチャンクだけ</b>を候補にして、その中から 1 つ選ぶ。
 * <ul>
 *     <li><b>間隔が保証される</b> —— 抽選で「たまたま隣に 2 つ」が起きない。
 *     器官は世界全体に効くので、数が読めないと効果の強さが設計できない。</li>
 *     <li><b>どの順番で極相になっても同じ場所に生える</b> —— 種と升目の座標だけで決まる。
 *     ledger を見て「近くに無いか」を確かめる方式だと<b>探索の順番で世界が変わる</b>。</li>
 *     <li><b>地脈が 1 本も通っていない升目には生えない。</b> まばらになるが、
 *     地脈から離れた所へ無理に置くより筋が通る。</li>
 * </ul>
 *
 * <p>候補地探しは<b>世界に一切触らない</b>({@link LeyVeinField} は座標だけの関数)ので、
 * 升目 1 つぶん(既定 576 チャンク)を走査してもチャンクの読み込みは起きない。
 * 結果は升目ごとに覚えておく。
 */
public final class OrganField {

    /** 抽選に使う塩。地脈や地域マップと模様が重ならないように別の値。 */
    private static final long SALT = 0x0F9A21C7B34DL;

    /** 1 チャンクに地脈が通っているかを見るときの標本点(チャンク内の相対座標)。 */
    private static final int[][] SAMPLES = {{8, 8}, {3, 3}, {12, 3}, {3, 12}, {12, 12}};

    private record CellKey(long seed, int cellX, int cellZ, int cellSize) {
    }

    /** 升目 → 候補地。{@code Optional} ではなく「無い」を入れておけないので null 番兵を使わない。 */
    private static final Map<CellKey, ChunkPos> CELLS = new ConcurrentHashMap<>();

    /** 「この升目には地脈が無い」を覚えておくための印(値に null を入れられないため)。 */
    private static final ChunkPos NONE = new ChunkPos(Integer.MIN_VALUE, Integer.MIN_VALUE);

    private OrganField() {
    }

    /** そのチャンクが、自分の属する升目の候補地か。 */
    public static boolean isSite(long seed, int chunkX, int chunkZ, int cellSizeChunks) {
        ChunkPos site = siteIn(seed, Math.floorDiv(chunkX, cellSizeChunks),
                Math.floorDiv(chunkZ, cellSizeChunks), cellSizeChunks);
        return site != null && site.x == chunkX && site.z == chunkZ;
    }

    /**
     * その升目の候補地。<b>地脈が通っているチャンクの中から</b>ハッシュがいちばん小さいものを選ぶ。
     *
     * @return 升目に地脈が 1 本も通っていなければ {@code null}
     */
    @Nullable
    public static ChunkPos siteIn(long seed, int cellX, int cellZ, int cellSizeChunks) {
        CellKey key = new CellKey(seed, cellX, cellZ, cellSizeChunks);
        ChunkPos cached = CELLS.get(key);
        if (cached != null) {
            return cached == NONE ? null : cached;
        }
        ChunkPos best = null;
        long bestHash = Long.MAX_VALUE;
        int originX = cellX * cellSizeChunks;
        int originZ = cellZ * cellSizeChunks;
        for (int dx = 0; dx < cellSizeChunks; dx++) {
            for (int dz = 0; dz < cellSizeChunks; dz++) {
                int chunkX = originX + dx;
                int chunkZ = originZ + dz;
                if (!hasVein(seed, chunkX, chunkZ)) {
                    continue;
                }
                long h = hash(seed, chunkX, chunkZ);
                if (h < bestHash) {
                    bestHash = h;
                    best = new ChunkPos(chunkX, chunkZ);
                }
            }
        }
        CELLS.put(key, best != null ? best : NONE);
        return best;
    }

    /** そのチャンクを地脈が通っているか。数点だけ見る(幅 5 の筋なので、これで十分拾える)。 */
    public static boolean hasVein(long seed, int chunkX, int chunkZ) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int[] sample : SAMPLES) {
            if (LeyVeinField.isVeinAt(seed, baseX + sample[0], baseZ + sample[1])) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code origin} からいちばん近い候補地。<b>生えているとは限らない</b> ——
     * 極相になって全層置換まで終わった候補地にしか器官は育たない。
     * デバッグコマンド({@code /ov organ site})が「どこを見に行けばいいか」を答えるのに使う。
     *
     * @return 周りの升目に 1 つも候補地が無ければ {@code null}
     */
    @Nullable
    public static ChunkPos nearestSite(long seed, ChunkPos origin, int cellSizeChunks) {
        int cellX = Math.floorDiv(origin.x, cellSizeChunks);
        int cellZ = Math.floorDiv(origin.z, cellSizeChunks);
        ChunkPos best = null;
        long bestDistance = Long.MAX_VALUE;
        // 隣の升目の候補地のほうが近いことがある(自分の升目の端に居るとき)。
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ChunkPos site = siteIn(seed, cellX + dx, cellZ + dz, cellSizeChunks);
                if (site == null) {
                    continue;
                }
                long ox = (long) site.x - origin.x;
                long oz = (long) site.z - origin.z;
                long distance = ox * ox + oz * oz;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = site;
                }
            }
        }
        return best;
    }

    /** 覚えていた升目を捨てる({@code /ov design reload} で升目の大きさが変わったとき)。 */
    public static void forget() {
        CELLS.clear();
    }

    private static long hash(long seed, int chunkX, int chunkZ) {
        long h = SALT ^ seed;
        h ^= chunkX * 0x9E3779B97F4A7C15L;
        h ^= chunkZ * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 29;
        return h >>> 1; // 符号を落として比較を素直にする
    }
}
