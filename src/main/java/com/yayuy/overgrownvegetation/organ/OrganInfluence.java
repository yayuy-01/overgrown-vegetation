package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.data.OrganTable;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/**
 * <b>器官が世界に及ぼしている力</b>(2026-09-23)。
 *
 * <h2>効果に距離が無い</h2>
 * <p>器官の本体である<b>下限</b>({@link #worldFloorPercent})は、
 * その次元の<b>どこに居ても同じ値</b>。胞子除去装置や忌避灯のような「半径いくつ」ではない。
 * 狙いは<b>引っ越して逃げられなくする</b>こと —— 汚染から遠ざかるのが今までの答えだったが、
 * 器官はそれを封じる。残るのは<b>潰しに行く</b>か<b>受け入れて暮らす</b>かの二択になる。
 *
 * <h2>症状が先、原因が後</h2>
 * <p>器官が生えても<b>何も知らせない</b>。警報も、地図の印も、進捗の通知も出さない。
 * プレイヤーが最初に気付くのは<b>症状</b> —— 拠点から汚染を遠ざけたのに
 * 胞子濃度がある値より下がらない、という形で出る。
 *
 * <p>だから原因を辿る道が要る。それが<b>まわりだけに乗る上乗せ</b>({@link #localPercentAt})。
 * 距離に比例して直線的に減るので、胞子濃度計を持って歩けば<b>数字が上がる方へ辿れる</b>。
 * 下限だけでは世界中が同じ値になって探しようがないので、この 2 本立てが要る。
 *
 * <h2>育ち具合に比例する</h2>
 * <p>下限も上乗せも {@code maturityDays} をかけて育つ。生えた直後は 0 なので、
 * <b>気付いたときにはもう効いている</b>。「今この瞬間に何かが起きた」と分かってしまうと、
 * 原因を探す面白さが消える。
 *
 * <h2>負荷</h2>
 * <p>{@code SporeConcentration#percentAt} は全 Mob から呼ばれる。
 * <b>器官が 1 つも無い世界では最初の 1 行で返る</b>のがいちばん大事
 * (ほとんどのセーブは最後までそうなる)。下限は台帳が変わるか
 * {@link #TTL_TICKS} 経つまで覚えておく —— 育ち具合は時間とともに動くので、
 * 台帳が変わらなくても作り直す必要がある。
 */
public final class OrganInfluence {

    /** 下限を覚えておく時間(tick)。育ち具合が動くので永久には持てない。 */
    private static final long TTL_TICKS = 100L;

    private record Cached(double floor, long computedGameTime) {
    }

    private static final Map<ResourceKey<Level>, Cached> FLOOR = new HashMap<>();

    private OrganInfluence() {
    }

    /** 台帳が変わった。覚えていた下限を捨てる({@link OrganData} から)。 */
    public static void forget() {
        FLOOR.clear();
    }

    /**
     * その次元の胞子濃度の<b>下限</b>(%)。どこに居ても同じ値。
     *
     * <p>育ちきった器官 1 つにつき {@code worldFloorPercent}、合計は {@code worldFloorMaxPercent} で頭打ち。
     * 頭打ちが無いと、器官が増え続けた世界が<b>全部 100%</b> になって
     * 「どこも同じ」に戻ってしまう(濃度の差が意味を失う)。
     */
    public static double worldFloorPercent(ServerLevel level) {
        OrganData data = OrganData.get(level);
        if (data.count() == 0) {
            return 0.0D;
        }
        long now = level.getGameTime();
        Cached cached = FLOOR.get(level.dimension());
        if (cached != null) {
            long age = now - cached.computedGameTime();
            if (age >= 0L && age < TTL_TICKS) {
                return cached.floor();
            }
        }
        OrganTable.SporeGland spec = OrganData.spec();
        double sum = 0.0D;
        for (Long2ObjectMap.Entry<OrganData.Organ> entry : data.nodes().long2ObjectEntrySet()) {
            sum += spec.worldFloorPercent() * OrganData.maturity(entry.getValue().born(), now, spec);
            if (sum >= spec.worldFloorMaxPercent()) {
                sum = spec.worldFloorMaxPercent();
                break;
            }
        }
        FLOOR.put(level.dimension(), new Cached(sum, now));
        return sum;
    }

    /**
     * そのチャンクに乗る<b>器官のまわりだけ</b>の上乗せ(%)。器官を探す唯一の手がかり。
     *
     * <p>いちばん強い 1 つぶんだけを返す(足し合わせない)。足すと
     * 「40% = 器官の真上」という読み方が崩れ、勾配を辿っても中心が分からなくなる。
     */
    public static double localPercentAt(ServerLevel level, ChunkPos chunk) {
        return localPercentAt(level, chunk.toLong());
    }

    /**
     * {@link #localPercentAt(ServerLevel, ChunkPos)} の、チャンク座標を詰めたまま受ける版。
     * 胞子濃度は全 Mob から呼ばれるので、器官が 1 つも無いときに {@link ChunkPos} すら作らせない。
     */
    public static double localPercentAt(ServerLevel level, long chunkKey) {
        OrganData data = OrganData.get(level);
        if (data.count() == 0) {
            return 0.0D;
        }
        OrganTable.SporeGland spec = OrganData.spec();
        double reach = spec.localReachBlocks();
        if (reach <= 0.0D || spec.localPercent() <= 0.0D) {
            return 0.0D;
        }
        long now = level.getGameTime();
        double centreX = (ChunkPos.getX(chunkKey) << 4) + 8;
        double centreZ = (ChunkPos.getZ(chunkKey) << 4) + 8;
        double best = 0.0D;
        for (Long2ObjectMap.Entry<OrganData.Organ> entry : data.nodes().long2ObjectEntrySet()) {
            long packed = entry.getLongKey();
            double dx = BlockPos.getX(packed) - centreX;
            double dz = BlockPos.getZ(packed) - centreZ;
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance >= reach) {
                continue;
            }
            double value = spec.localPercent() * (1.0D - distance / reach)
                    * OrganData.maturity(entry.getValue().born(), now, spec);
            if (value > best) {
                best = value;
            }
        }
        return best;
    }
}
