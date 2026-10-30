package com.yayuy.overgrownvegetation.defense;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 機関砲台が「いまこの相手は自分が撃っている」と立てる<b>札</b>(ユーザー要望 2026-09-26
 * 「複数の機関砲台があると、すべてが同じ目標を狙ってしまう」)。
 *
 * <h2>取り合いではなく、並べ替えの手がかり</h2>
 * <p>札の立っている相手を<b>撃ってはいけない</b>わけではない —— 同じ優先度の中で<b>後ろに回す</b>だけ
 * ({@code GunTurretTargeting} の並べ方: 優先度 → 札 → 向ける手間)。禁止にすると、相手が 1 体しか居ないときに
 * どの砲台も「あれは他が撃っている」と譲り合って<b>誰も撃たない</b>ことになる。
 * 数が足りているときは自然に散り、足りないときは全台で同じ相手を叩く。
 *
 * <h2>片付け</h2>
 * <p>札は<b>置いた砲台が毎 tick 立て直す</b>({@link #claim})。立て直されない札は {@link #TTL_TICKS} で
 * 効かなくなるので、砲台が壊れても・チャンクが外れても・ワールドが落ちても勝手に消える。
 * 目標を替えた砲台は自分の札を外す({@link #release}) —— 待たずにその相手を他の砲台へ譲るため。
 */
public final class GunTurretClaims {

    /** 立て直されない札が効かなくなるまでの tick。砲台は毎 tick 立て直すので、短くてよい。 */
    private static final long TTL_TICKS = 40L;
    /** これを超えたら、札を立てるついでに古いものを掃除する。 */
    private static final int PRUNE_SIZE = 64;

    /** 札 1 枚。{@code owner} は砲台のブロック座標、{@code renewedAt} は最後に立て直したゲーム内時刻。 */
    private record Claim(long owner, long renewedAt) {
    }

    private static final Map<ResourceKey<Level>, Map<Long, Claim>> CLAIMS = new ConcurrentHashMap<>();

    /** 目標の鍵(種類 + id)。 */
    public static long key(int kind, int id) {
        return ((long) kind << 32) | (id & 0xFFFFFFFFL);
    }

    /** {@code owner} の砲台が、その相手を撃っていることにする(毎 tick 呼ぶ)。 */
    public static void claim(ServerLevel level, BlockPos owner, int kind, int id) {
        Map<Long, Claim> map = CLAIMS.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>());
        long now = level.getGameTime();
        if (map.size() > PRUNE_SIZE) {
            map.values().removeIf(claim -> now - claim.renewedAt() > TTL_TICKS);
        }
        map.put(key(kind, id), new Claim(owner.asLong(), now));
    }

    /** {@code owner} が立てていた札を外す(目標を替えたとき)。他の砲台の札は触らない。 */
    public static void release(ServerLevel level, BlockPos owner, int kind, int id) {
        Map<Long, Claim> map = CLAIMS.get(level.dimension());
        if (map == null) {
            return;
        }
        long key = key(kind, id);
        Claim claim = map.get(key);
        if (claim != null && claim.owner() == owner.asLong()) {
            // 値ごと一致したときだけ消す(その隙に別の砲台が立て直していたら触らない)。
            map.remove(key, claim);
        }
    }

    /** その相手に<b>他の砲台</b>の生きている札が立っているか。 */
    public static boolean claimedByOther(ServerLevel level, BlockPos self, int kind, int id) {
        Map<Long, Claim> map = CLAIMS.get(level.dimension());
        if (map == null || map.isEmpty()) {
            return false;
        }
        Claim claim = map.get(key(kind, id));
        return claim != null
                && claim.owner() != self.asLong()
                && level.getGameTime() - claim.renewedAt() <= TTL_TICKS;
    }

    public static void clearAll() {
        CLAIMS.clear();
    }

    private GunTurretClaims() {
    }
}
