package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 湧いた Mob が<b>地面やブロックに埋まらない</b>ようにする。
 *
 * <p>ユーザー報告(2026-09-14):「当たり判定が 1 以上の Mob(アルボラ・アースロプレウラなど)が
 * 湧いたときに地面へ埋まってダメージを受けている」。原因は湧かせる側の空き判定が
 * <b>1 マスの柱しか見ていなかった</b>こと — 幅 2.0 のスポロフィルや幅 2.5 の大盾は隣のマスにも
 * 体がはみ出すので、柱が空いていても横のブロックへめり込む。高さも「足元+1 マス」しか見ておらず、
 * 背の高い種(大盾 2.0 / 汚染シダ 2.6)では頭が埋まる。
 *
 * <p>対処は 2 段構え:
 * <ol>
 *     <li>{@link #fits} — 湧かせる前に<b>体の当たり判定まるごと</b>({@link EntityType#getSpawnAABB})が
 *     空いているか見る。MOD 側の湧かせ処理はすべてここを通す。体格({@code scale})と個体差の上限
 *     ({@code scaleMax})の分は、{@code ModEntities} で {@code spawnDimensionsScale} としてこの箱に掛けてある
 *     (掛けないと素の大きさで見てしまう。2026-09-23)。</li>
 *     <li>{@link #unbury} — それでも埋まった状態で世界に入ってきた個体(スポーンエッグ・{@code /summon}・
 *     他 MOD 経由など)を、{@link EntityJoinLevelEvent} の時点で近くの空きへ押し出す。
 *     見る箱は SCALE 込みの大きさ({@link #bodyAt})。</li>
 * </ol>
 *
 * <p>その場に根を張る側の Mob(汚染シダ・巣の節・苗床)は、置かれた位置そのものに意味があるので
 * 動かさない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SpawnFit {

    /** 上へ逃がす最大距離(地面に埋まっている典型例はこれで抜ける)。 */
    private static final int MAX_UP = 3;
    /** 下へ逃がす最大距離。 */
    private static final int MAX_DOWN = 1;
    /** 横へ逃がす最大距離。 */
    private static final int MAX_SIDE = 2;
    /** 「足場がある」とみなす下方向の探索距離(空中へ放り出さないため)。 */
    private static final double GROUND_SEARCH = 2.5D;

    /** 逃がし先の候補({@code dx, dy, dz})。元の位置に近い順。 */
    private static final List<int[]> OFFSETS = buildOffsets();

    private static List<int[]> buildOffsets() {
        List<int[]> offsets = new ArrayList<>();
        for (int dx = -MAX_SIDE; dx <= MAX_SIDE; dx++) {
            for (int dz = -MAX_SIDE; dz <= MAX_SIDE; dz++) {
                for (int dy = -MAX_DOWN; dy <= MAX_UP; dy++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        offsets.add(new int[] {dx, dy, dz});
                    }
                }
            }
        }
        offsets.sort(Comparator.comparingDouble(SpawnFit::score));
        return List.copyOf(offsets);
    }

    /** 近いほど小さい。下へ落とすのは最後の手段なので重くする。 */
    private static double score(int[] offset) {
        double dy = offset[1];
        return offset[0] * offset[0] + offset[2] * offset[2] + dy * dy * (dy < 0.0D ? 1.5D : 1.0D);
    }

    private SpawnFit() {
    }

    /**
     * {@code pos}(足元のマス)に {@code type} の体がまるごと入るか。
     * 幅も高さも見るので、幅 1 を超える種や背の高い種でも埋まらない。
     * 体格の分は {@code spawnDimensionsScale}({@code MobStats#spawnScale})で箱に入っている。
     */
    public static boolean fits(Level level, EntityType<?> type, BlockPos pos) {
        return fitsAt(level, type, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
    }

    /** 足元の座標を直接指定する版(水中など、マスの中心に立たせない湧かせ方用)。 */
    public static boolean fitsAt(Level level, EntityType<?> type, double x, double y, double z) {
        return level.noCollision(type.getSpawnAABB(x, y, z));
    }

    /**
     * 埋まっている個体を、足場のある近くの空きへ押し出す(元の位置に近い順に探す)。
     * 逃がせなければ何もしない(バニラ同様、窒息ダメージを受ける)。
     *
     * @return 動かしたら {@code true}
     */
    public static boolean unbury(Mob mob) {
        Level level = mob.level();
        Vec3 from = mob.position();
        if (free(level, mob, from)) {
            return false;
        }
        boolean needsGround = needsGround(mob);
        for (int[] offset : OFFSETS) {
            Vec3 candidate = new Vec3(from.x + offset[0], from.y + offset[1], from.z + offset[2]);
            if (free(level, mob, candidate) && (!needsGround || supported(level, mob, candidate))) {
                mob.moveTo(candidate.x, candidate.y, candidate.z, mob.getYRot(), mob.getXRot());
                return true;
            }
        }
        return false;
    }

    /** その位置に体が入るか。 */
    private static boolean free(Level level, Mob mob, Vec3 pos) {
        return level.noCollision(mob, bodyAt(mob, pos));
    }

    /**
     * {@code pos} に立ったときの体の箱。<b>最初の tick の後の大きさ</b>(SCALE 込み)で見る。
     *
     * <p>{@link EntityJoinLevelEvent} の時点では、体格({@code mobs.json} の {@code scale})を持つ種の当たり判定は
     * まだ素の大きさ(SCALE が掛かるのは {@code LivingEntity#tick} の終わり)。{@code getBoundingBox()} を
     * 平行移動して見ると、素の体なら入る隙間を「空いている」とみなしてしまい、次の tick で育って埋まる
     * (2026-09-23)。個体差のある種は {@link MobSizeVariance} がこの前に当たり判定を作り直しているので、どちらでも同じ。
     */
    private static AABB bodyAt(Mob mob, Vec3 pos) {
        return mob.getDimensions(mob.getPose()).makeBoundingBox(pos);
    }

    /** 泳ぐ種・飛ぶ種は足場を要らない(水中・空中の逃がし先を弾かないように)。 */
    private static boolean needsGround(Mob mob) {
        return !(mob instanceof AquaticOvergrownMob) && !(mob instanceof OvergrownFlyerEntity) && !mob.isNoGravity();
    }

    /** 足元 {@value #GROUND_SEARCH} ブロック以内に足場があるか(空中へ放り出さない)。 */
    private static boolean supported(Level level, Mob mob, Vec3 pos) {
        AABB box = bodyAt(mob, pos);
        AABB below = new AABB(box.minX, box.minY - GROUND_SEARCH, box.minZ, box.maxX, box.minY - 1.0E-4D, box.maxZ);
        return !level.noCollision(below);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof PathfinderMob mob)) {
            return;
        }
        if (!EntityType.getKey(mob.getType()).getNamespace().equals(OvergrownVegetation.MODID)) {
            return;
        }
        if (isRooted(mob) || mob.isNoAi() || mob.isPassenger()) {
            return;
        }
        unbury(mob);
    }

    /** その場に根を張る/据え付けられる側 — 位置そのものに意味があるので動かさない。 */
    private static boolean isRooted(PathfinderMob mob) {
        return mob instanceof BlightFernEntity
                || mob instanceof HiveNodeEntity
                || mob instanceof OvergrownNexusEntity
                // ヴォラムネス(2026-10-01): 地中をすり抜ける(noPhysics)。地上に出た体(2.5 × 8.5)で読み込まれると葉・天井に当たって
                // 横や宙へ押し出されてしまう。置き場所は自分で地表へ合わせる(VoramnesEntity#tickStalk)。
                || mob instanceof VoramnesEntity
                // フォディムネス(2026-10-01): 岩の中で眠って聞くのが本来の居場所(湧きの関門で岩の中へ潜ってから世界に足す)。
                // 押し出すと、湧いた直後・チャンクを読み込み直すたびに洞窟へ放り出される。地形をすり抜けるので窒息もしない
                || mob instanceof FodimnesEntity;
    }
}
