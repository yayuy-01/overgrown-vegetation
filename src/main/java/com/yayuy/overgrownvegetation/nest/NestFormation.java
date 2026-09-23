package com.yayuy.overgrownvegetation.nest;

import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 巣を掘り始める入口(2026-09-19)。
 *
 * <p><b>きっかけは 2026-09-20 に {@link BuilderGroups} へ移した。</b>
 * それまでは「アルボラが 1 か所に 15 体集まったらその場に掘る」だったが、これだと巣は必ず
 * 過繫茂バイオームのど真ん中にできる。今は<b>建設グループ</b>が過繫茂バイオームから離れた
 * 地点まで歩いていって掘るので、汚染が外へ広がる動きと結びつく(ユーザー指定)。
 *
 * <p>ここに残っているのは「{@code origin} に 1 つ設計して掘削キューへ積む」だけ。
 * 呼ぶのは {@link BuilderGroups} と {@code /ov nest start}({@link NestCommand})。
 */
public final class NestFormation {

    /** {@code origin} に巣を 1 つ設計して、掘削キューへ積む。 */
    public static boolean start(ServerLevel level, BlockPos origin) {
        return start(level, origin, level.random.nextLong());
    }

    /**
     * 種を指定して掘り始める。<b>掘りかけの巣を読み直すときに同じ形を組み立て直す</b>ために使う
     * ({@link NestDigData}、2026-09-20)。形は入口・種・設計値だけで決まるので、
     * 1 万マスの座標を保存しなくても再現できる。
     */
    public static boolean start(ServerLevel level, BlockPos origin, long seed) {
        NestLayout.Plan plan = buildPlan(level, origin, seed);
        if (plan.isEmpty()) {
            return false;
        }
        NestGrowthQueue.enqueue(level, plan, seed);
        return true;
    }

    /**
     * 設計を 1 つ組み立てる。地形の高さを教えるのは<b>噴出孔の柱を実際の地面まで届かせるため</b>
     * だけで、掘る場所({@code hollow})には影響しない —— だから塚を盛ったあとに組み立て直しても
     * 「どこまで掘ったか」の数え方はずれない。
     */
    static NestLayout.Plan buildPlan(ServerLevel level, BlockPos origin, long seed) {
        return NestLayout.build(origin, seed, DesignData.nest(), new NestLayout.Terrain() {
            @Override
            public int surfaceAt(int x, int z) {
                return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        x, z);
            }

            @Override
            public int seabedAt(int x, int z) {
                // 水を数えない地表。surfaceAt より低ければ、その差ぶんが水(2026-09-21)。
                return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, x, z);
            }
        });
    }

    private NestFormation() {
    }
}
