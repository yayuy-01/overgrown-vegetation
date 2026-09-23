package com.yayuy.overgrownvegetation.nest;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>掘り手の割り当て</b>(2026-09-20、ユーザー指定の折衷案)。
 *
 * <p>巣の形は今までどおり {@link NestLayout} が先に骨組みとして決める。変わったのは
 * <b>誰が掘るか</b> —— 建設グループのアルボラが実際に切羽(掘っている先端)まで歩いてきて、
 * <b>その場にいるあいだだけ</b>掘り進む({@link NestGrowthQueue})。
 *
 * <h2>なぜ骨組みを残すのか</h2>
 * <p>完全に各個体の判断だけで掘らせると、「必ず繋がっている」「噴気室がちょうど 1 室」
 * 「縦穴が無い」「掘る量が上限を超えない」といった<b>保証が確率に変わる</b>。
 * これらは全体を見渡せる今の作りですら何度も壊れた(2026-09-19〜20 の修正の大半がこれ)。
 * 骨組みを残せば保証とテストはそのままに、<b>地形への反応</b>と<b>掘っている姿</b>だけを足せる。
 *
 * <h2>割り当ての持ち方</h2>
 * <p>ワールドには保存しない。掘り手の UUID → 今向かうべき切羽、という表をメモリに置くだけで、
 * 掘り終われば消える。個体が死んでもチャンクが落ちても、次の tick に数え直されるので後始末が要らない
 * ({@link com.yayuy.overgrownvegetation.entity.InvaderPackBond} と同じ考え方)。
 */
public final class NestCrew {

    /** 掘り手の UUID → 今向かうべき切羽。 */
    private static final Map<UUID, BlockPos> FACES = new ConcurrentHashMap<>();
    /** 掘り手の UUID → 実際に立つ場所(切羽のまわりに散らした点)。 */
    private static final Map<UUID, BlockPos> STANDS = new ConcurrentHashMap<>();

    /**
     * 切羽のまわりに掘り手を散らす並び(2026-09-21 のユーザー報告
     * 「建設中に一か所に集まってとどまってしまう」)。
     *
     * <p>全員に同じ 1 マスを指すと、<b>互いに押し合って切羽から弾き出される</b> ——
     * 弾かれると経路を引き直し、また押し合う。見た目には「団子になって止まっている」。
     * 掘り手ごとに違う立ち位置を配れば押し合いが起きない。
     */
    private static final int[][] RING = {
            {0, 0}, {2, 0}, {-2, 0}, {0, 2}, {0, -2},
            {2, 2}, {-2, 2}, {2, -2}, {-2, -2}, {3, 0}, {-3, 0}, {0, 3}, {0, -3},
    };

    /** 掘り手がそこに「着いている」とみなす水平距離。ここに居るあいだだけ掘り進む。 */
    public static final double REACH = 6.0D;
    /**
     * 切羽と同じ高さ帯に居ることも求める(2026-09-20 のユーザー報告
     * 「巣の外から巣を構築し始める」)。距離だけで数えると、<b>浅い切羽の真上の地面に立っている</b>
     * だけで掘り手として数えられてしまい、地上に突っ立ったまま巣が出来上がる。
     */
    public static final int REACH_Y = 3;

    /**
     * いま掘っている巣の<b>道しるべ</b>({@code NestLayout.Plan} のもの)。
     * 掘り手はこれを辿って切羽まで潜る({@code entity/NestDigGoal})。
     *
     * <p>静的に 1 つだけ持てるのは、<b>建設が世界に 1 つだけ</b>だから
     * ({@code BuilderGroups.activeGroup}、2026-09-20 のユーザー指定)。
     */
    private static List<BlockPos> route = List.of();
    private static int[] routeParents = new int[0];

    /** 掘り始めた巣の道しるべを憶える。 */
    public static void setDig(List<BlockPos> waypoints, int[] parents) {
        route = List.copyOf(waypoints);
        routeParents = parents.clone();
    }

    /** 掘り終わった(または諦めた)。 */
    public static void clearDig() {
        route = List.of();
        routeParents = new int[0];
    }

    public static List<BlockPos> route() {
        return route;
    }

    public static int[] routeParents() {
        return routeParents;
    }

    static void setFace(UUID id, BlockPos face, int slot) {
        FACES.put(id, face.immutable());
        int[] offset = RING[Math.floorMod(slot, RING.length)];
        STANDS.put(id, face.offset(offset[0], 0, offset[1]).immutable());
    }

    /** {@code entity} が実際に立つ場所(切羽のまわりに散らした点)。掘り手でなければ null。 */
    @Nullable
    public static BlockPos standFor(Entity entity) {
        return entity == null ? null : STANDS.get(entity.getUUID());
    }

    static void clear(UUID id) {
        FACES.remove(id);
        STANDS.remove(id);
    }

    /** {@code entity} が今向かうべき切羽。掘り手でなければ null。 */
    @Nullable
    public static BlockPos faceFor(Entity entity) {
        return entity == null ? null : FACES.get(entity.getUUID());
    }

    public static boolean isDigger(Entity entity) {
        return entity != null && FACES.containsKey(entity.getUUID());
    }

    /** ワールドの読み直しで古い割り当てが残らないように(サーバー停止時)。 */
    public static void clearAll() {
        FACES.clear();
        STANDS.clear();
        clearDig();
    }

    private NestCrew() {
    }
}
