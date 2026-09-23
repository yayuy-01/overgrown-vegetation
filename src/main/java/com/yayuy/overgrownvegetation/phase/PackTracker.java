package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.registry.ModAttachments;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 「群れ」を数えるための札(2026-09-21)。
 *
 * <p>生態系スポーン({@link OvergrownBiomeSpawner})が 1 か所にまとめて湧かせたひとかたまりを
 * <b>1 群れ</b>とし、その全員に同じ群れ番号({@link ModAttachments#PACK_ID})を付ける。
 * {@code phases.json} の {@code maxPacks} は「ワールドに同時に存在できる群れの数」の上限で、
 * 上限に達している間は<b>新しい群れを湧かせない</b>(既にいる群れには何もしない)。
 *
 * <p>数え方は「ロード済みのエンティティが持っている群れ番号の種類数」。未ロードのチャンクにいる
 * 群れは数に入らない —— 逆に言えば「プレイヤーから遠く離れて眠っている群れ」が枠を食い潰すことはない。
 * 群れ番号はセーブされるので、チャンクが読み込まれ直せばまた同じ群れとして数えられる。
 *
 * <p>1 体だけの群れも 1 群れと数える(スポーン地点が 1 つなら 1 群れ)。
 */
public final class PackTracker {

    /** 次に配る群れ番号。ワールドをまたいで一意である必要はない(生きている群れを数えるだけ)。 */
    private static final AtomicInteger NEXT = new AtomicInteger(1);

    // 同じ tick の間は数え直さない(スポーン判定はプレイヤーごとに走るため)。
    private static ServerLevel cachedLevel;
    private static long cachedTick = Long.MIN_VALUE;
    private static int cachedCount;

    /** 新しい群れ番号を 1 つ取る。 */
    public static int newPack() {
        return NEXT.updateAndGet(v -> v == Integer.MAX_VALUE ? 1 : v + 1);
    }

    /** 湧かせた個体に群れ番号を付ける({@code packId} が 0 なら何もしない)。 */
    public static void mark(Entity entity, int packId) {
        if (packId != 0) {
            entity.setData(ModAttachments.PACK_ID, packId);
        }
    }

    /** いまワールド(ロード済みの範囲)に存在する群れの数。 */
    public static int countPacks(ServerLevel level) {
        long now = level.getGameTime();
        if (level == cachedLevel && now == cachedTick) {
            return cachedCount;
        }
        IntOpenHashSet ids = new IntOpenHashSet();
        for (Mob mob : level.getEntities(EntityTypeTest.forClass(Mob.class),
                m -> m.hasData(ModAttachments.PACK_ID))) {
            int id = mob.getData(ModAttachments.PACK_ID);
            if (id != 0) {
                ids.add(id);
            }
        }
        cachedLevel = level;
        cachedTick = now;
        cachedCount = ids.size();
        return cachedCount;
    }

    /**
     * このフェーズであと何群れ湧かせてよいか。{@code maxPacks} が 0(無制限)なら
     * {@link Integer#MAX_VALUE}。
     */
    public static int budget(ServerLevel level, int phase) {
        int max = com.yayuy.overgrownvegetation.data.DesignData.phases().maxPacks(phase);
        return max > 0 ? Math.max(0, max - countPacks(level)) : Integer.MAX_VALUE;
    }

    private PackTracker() {
    }
}
