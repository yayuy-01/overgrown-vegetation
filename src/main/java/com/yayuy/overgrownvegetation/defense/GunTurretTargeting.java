package com.yayuy.overgrownvegetation.defense;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import com.yayuy.overgrownvegetation.entity.BlightWaterGrassEntity;
import com.yayuy.overgrownvegetation.entity.VelophyllBombEntity;
import com.yayuy.overgrownvegetation.entity.VelophyllEntity;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.siege.CannonBallistics;
import com.yayuy.overgrownvegetation.siege.ShellTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 機関砲台({@code block/GunTurretBlockEntity})の<b>目標選び</b>(2026-09-21)。
 *
 * <h2>優先順位(ユーザー指定)</h2>
 * <ol>
 *   <li><b>落としに来るもの</b> —— ヴェロフィル本体、落下中の胞子爆弾({@link VelophyllBombEntity}:
 *       ヴェロフィルの爆撃と、過繫茂したスカトゥリウムの迫撃砲)、そして<b>攻城戦ボスの曲射</b>
 *       ({@link ShellTracker#KIND_SPORE} —— 普段の迫撃砲と、増援を運ぶ胞子弾の両方)。</li>
 *   <li>それが無ければ、射程かつ仰俯角の可動域に入っている普通の敵。</li>
 * </ol>
 * 同じ優先度のなかでは<b>近い順</b>。
 *
 * <h2>撃てる相手かどうか({@link #canEngage})</h2>
 * 射程(半径)の中にいて、砲耳から見た仰角が可動域({@code defense.json} の
 * {@code gunTurret.minElevation}〜{@code maxElevation} = 既定 −28〜+85 度)に入っていて、
 * 砲耳から相手までブロックに遮られていないこと({@link #blockedAt})。
 * <b>射程は相手で変わる</b>({@link #rangeFor}) —— ヴェロフィルだけ {@code velophyllRange}(既定 64)。
 */
public final class GunTurretTargeting {

    /** 目標の種類: エンティティ。 */
    public static final int KIND_ENTITY = 1;
    /** 目標の種類: 飛行中の胞子弾({@link ShellTracker})。エンティティではないので id は弾の識別子。 */
    public static final int KIND_SHELL = 2;

    /** 最優先で落とす相手か。 */
    private static final int RANK_INTERCEPT = 0;
    private static final int RANK_NORMAL = 1;

    /** 砲台自身のブロックを避けるために、視線の起点を前へ出す距離(ブロック)。 */
    private static final double SELF_SKIP = 1.05D;

    /** 見つかった目標。{@code point} は<b>そのときの</b>狙点(次の tick には動いている)。 */
    public record Target(int kind, int id, Vec3 point) {
    }

    /** 候補 1 件(優先度と距離で並べ替えるための入れ物)。{@code range} はその相手に使う射程。 */
    private record Candidate(int rank, double distanceSq, int kind, int id, Vec3 point, double range) {
    }

    /**
     * いちばん撃つべき相手。射程の外・可動域の外・遮蔽の向こうの相手は最初から候補に入らない。
     *
     * <p>射程は相手によって違う({@link #rangeFor}) —— <b>ヴェロフィルだけは倍の
     * {@code gunTurret.velophyllRange}</b>(既定 64)。爆弾を抱えて来る相手は、落とされる前に
     * 遠くで仕留めたいので特別扱いしている(ユーザー指定 2026-09-21)。
     *
     * @return 撃つ相手({@code null} = 撃つものが無い)
     */
    @Nullable
    public static Target pick(ServerLevel level, BlockPos pos, double azimuthNow) {
        Vec3 trunnion = GunTurretGeometry.trunnion(pos, azimuthNow);
        double range = DesignData.defense().turretRange();
        double searchRange = Math.max(range, DesignData.defense().turretVelophyllRange());
        List<Candidate> candidates = new ArrayList<>();

        // 1. 飛んでくる胞子弾(ボスの迫撃砲・増援を運ぶ弾)。エンティティではないので別口で集める。
        for (ShellTracker.Incoming incoming : ShellTracker.get(level).sporeShellsNear(level, trunnion, range)) {
            candidates.add(new Candidate(RANK_INTERCEPT, incoming.pos().distanceToSqr(trunnion),
                    KIND_SHELL, incoming.id(), incoming.pos(), range));
        }

        // 2. エンティティ。探す箱はいちばん長い射程で取り、1 体ずつその相手の射程で切る。
        AABB box = new AABB(trunnion, trunnion).inflate(searchRange);
        for (Entity entity : level.getEntities((Entity) null, box, GunTurretTargeting::isEnemy)) {
            Vec3 point = aimPoint(entity, 1.0F);
            candidates.add(new Candidate(rank(entity), point.distanceToSqr(trunnion),
                    KIND_ENTITY, entity.getId(), point, rangeFor(entity)));
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator.<Candidate>comparingInt(Candidate::rank)
                .thenComparingDouble(Candidate::distanceSq));

        for (Candidate candidate : candidates) {
            if (canEngage(level, pos, azimuthNow, candidate.point(), candidate.range())) {
                return new Target(candidate.kind(), candidate.id(), candidate.point());
            }
        }
        return null;
    }

    /** その相手に使う射程。ヴェロフィルだけ {@code gunTurret.velophyllRange}(既定 64)。 */
    public static double rangeFor(@Nullable Entity entity) {
        return entity instanceof VelophyllEntity
                ? DesignData.defense().turretVelophyllRange()
                : DesignData.defense().turretRange();
    }

    /** 狙点。生き物は当たり判定の中心(足元でも頭上でもなく、いちばん当てやすいところ)。 */
    public static Vec3 aimPoint(Entity entity, float partialTick) {
        Vec3 pos = entity.getPosition(partialTick);
        return new Vec3(pos.x, pos.y + entity.getBbHeight() * 0.5D, pos.z);
    }

    /**
     * その点を<b>いま撃てる</b>か(射程・仰俯角の可動域・遮蔽)。
     *
     * @param azimuthNow いまの方位。砲耳の位置がわずかに方位で動くので渡す。
     */
    public static boolean canEngage(Level level, BlockPos pos, double azimuthNow, Vec3 point, double range) {
        Vec3 trunnion = GunTurretGeometry.trunnion(pos, azimuthNow);
        if (point.distanceToSqr(trunnion) > range * range) {
            return false;
        }
        double[] aim = GunTurretGeometry.aimAt(trunnion, point);
        if (!GunTurretGeometry.elevationInRange(aim[0])) {
            return false;
        }
        return blockedAt(level, pos, azimuthNow, point) == null;
    }

    /**
     * 砲台からその点までの弾道が地形に遮られるか。<b>遮られていれば当たる点</b>、通っていれば {@code null}。
     *
     * <h2>起点は砲耳(銃口ではない)</h2>
     * <p>銃身はモデルの倍率しだいで 3 ブロック以上伸びる({@code modelScale} 2.25 で 3.5 ブロック)。
     * 銃口を起点にすると、<b>近い相手では銃口の方が先に出てしまい</b>、見下ろして撃つときは
     * <b>銃口が地面にめり込んで「常に遮られている」</b>ことになる。弾の出どころ(見た目)は銃口のままで、
     * 当たり判定はこの砲耳からの線で決める。
     *
     * <p>砲台自身のブロックに当たった場合は<b>その先から引き直す</b> —— 倍率を小さくすると
     * 砲耳が自分のブロックの中に入るため。
     */
    @Nullable
    public static Vec3 blockedAt(Level level, BlockPos pos, double azimuthNow, Vec3 point) {
        Vec3 from = GunTurretGeometry.trunnion(pos, azimuthNow);
        Vec3 hit = clipTo(level, from, point);
        if (hit == null || !BlockPos.containing(hit).equals(pos)) {
            return hit;
        }
        // 自分のブロックの中から始まった。1 マスぶん先へ出てから引き直す。
        Vec3 path = point.subtract(from);
        double length = path.length();
        if (length <= SELF_SKIP) {
            return null;
        }
        return clipTo(level, from.add(path.scale(SELF_SKIP / length)), point);
    }

    /** {@code from} → {@code to} で最初に当たるブロックの位置(当たらなければ null)。 */
    @Nullable
    private static Vec3 clipTo(Level level, Vec3 from, Vec3 to) {
        HitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getLocation() : null;
    }

    /** 撃ってよい相手か。プレイヤー・家畜・動かない汚染植物は撃たない。 */
    public static boolean isEnemy(Entity entity) {
        if (!entity.isAlive()) {
            return false;
        }
        // 落ちてくる胞子爆弾(ヴェロフィルの爆撃・スカトゥリウムの迫撃砲)。生き物ではない。
        if (entity instanceof VelophyllBombEntity) {
            return true;
        }
        if (!(entity instanceof LivingEntity) || entity instanceof Player) {
            return false;
        }
        // 汚染シダ・汚染水草は動かない飾りなので弾の無駄。
        if (entity instanceof BlightFernEntity || entity instanceof BlightWaterGrassEntity) {
            return false;
        }
        // 過繫茂側(グループ1〜7)と、バニラの敵対 Mob。
        return MobGroup.of(entity.getType()) != null || entity instanceof Enemy;
    }

    /** 優先度。ヴェロフィルと落下中の爆弾は「落としに来るもの」なので最優先。 */
    private static int rank(Entity entity) {
        return entity instanceof VelophyllBombEntity || entity instanceof VelophyllEntity
                ? RANK_INTERCEPT : RANK_NORMAL;
    }

    /**
     * 銃身の向きと目標の向きの<b>あいだの角度</b>(度)。
     *
     * <p>仰角の差と方位の差を別々に足すのではなく、方向ベクトルどうしの角度で測る ——
     * ほぼ真上を向いているときは方位が大きくずれていても銃口はほとんど動かないので、
     * 差を足す測り方だと「真上の目標にいつまでも撃たない」ことになる。
     */
    public static double aimError(double elevation, double azimuth, double targetElevation, double targetAzimuth) {
        Vec3 now = CannonBallistics.direction(elevation, azimuth);
        Vec3 want = CannonBallistics.direction(targetElevation, targetAzimuth);
        double dot = Math.max(-1.0D, Math.min(1.0D, now.dot(want)));
        return Math.toDegrees(Math.acos(dot));
    }

    private GunTurretTargeting() {
    }
}
