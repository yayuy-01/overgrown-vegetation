package com.yayuy.overgrownvegetation.defense;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.block.GunTurretMode;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import com.yayuy.overgrownvegetation.entity.OvergrownFlyerEntity;
import com.yayuy.overgrownvegetation.entity.VelophyllBombEntity;
import com.yayuy.overgrownvegetation.entity.VelophyllEntity;
import com.yayuy.overgrownvegetation.entity.VoramnesEntity;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.siege.CannonBallistics;
import com.yayuy.overgrownvegetation.siege.ShellTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
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
 * 同じ優先度のなかでは<b>銃身を早く向けられる順</b>({@link #engageCost} = 旋回・俯仰にかかる tick + 距離の重み)。
 * 撃てる目標を持っている砲台は、優先度が上の相手が出るまで乗り換えない({@code GunTurretBlockEntity#tickServer})。
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
    public static final int RANK_INTERCEPT = 0;
    public static final int RANK_NORMAL = 1;

    /**
     * 同じ優先度の中で、距離 1 ブロックを旋回の何 tick ぶんと見るか。仮決め 2026-09-25: 2 tick(= 旋回 2.25 度)。
     * 5 ブロック先で 45 度横の相手は、30 ブロック先の正面の相手より先(40 + 10 &lt; 0 + 60)、真後ろなら後(160 + 10)。
     */
    private static final double DISTANCE_COST = 2.0D;

    /** 砲台自身のブロックを避けるために、視線の起点を前へ出す距離(ブロック)。 */
    private static final double SELF_SKIP = 1.05D;

    /** 見つかった目標。{@code point} は<b>そのときの</b>狙点(次の tick には動いている)。 */
    public record Target(int kind, int id, Vec3 point) {
    }

    /**
     * 候補 1 件(優先度と向ける手間で並べ替えるための入れ物)。{@code range} はその相手に使う射程、
     * {@code claimed} は<b>他の砲台が既に撃っている</b>か({@link GunTurretClaims})。
     */
    private record Candidate(int rank, boolean claimed, double cost, int kind, int id, Vec3 point, double range) {
        int claimOrder() {
            return claimed ? 1 : 0;
        }
    }

    /**
     * 候補の並べ方: 優先度 → <b>他の砲台が撃っていないもの</b> → 銃身を早く向けられる順({@link #engageCost})。
     *
     * <p>札({@link GunTurretClaims})を<b>禁止ではなく後回し</b>にしているのは、相手が 1 体しか居ないときに
     * 全台が譲り合って誰も撃たなくなるのを避けるため(ユーザー要望 2026-09-26「すべてが同じ目標を狙ってしまう」)。
     */
    private static final Comparator<Candidate> ORDER = Comparator.<Candidate>comparingInt(Candidate::rank)
            .thenComparingInt(Candidate::claimOrder)
            .thenComparingDouble(Candidate::cost);

    /**
     * いちばん撃つべき相手。射程の外・可動域の外・遮蔽の向こうの相手は最初から候補に入らない。
     *
     * <p>射程は相手によって違う({@link #rangeFor}) —— <b>ヴェロフィルだけは倍の
     * {@code gunTurret.velophyllRange}</b>(既定 64)。爆弾を抱えて来る相手は、落とされる前に
     * 遠くで仕留めたいので特別扱いしている(ユーザー指定 2026-09-21)。
     *
     * @param elevationNow いまの仰角(向ける手間 {@link #engageCost} に使う)
     * @param rankBelow    この優先度の数字より小さい(= 上の)相手だけを探す。全部なら {@code Integer.MAX_VALUE}
     *                     (撃てる目標を持っている砲台が「乗り換えるべき相手」だけを探すとき)
     * @return 撃つ相手({@code null} = 撃つものが無い)
     */
    @Nullable
    public static Target pick(ServerLevel level, BlockPos pos, double azimuthNow, double elevationNow, int rankBelow,
                              GunTurretMode mode) {
        Vec3 trunnion = GunTurretGeometry.trunnion(pos, azimuthNow);
        double range = DesignData.defense().turretRange();
        double searchRange = Math.max(range, DesignData.defense().turretVelophyllRange());
        List<Candidate> candidates = new ArrayList<>();
        Aim aim = new Aim(level, trunnion, azimuthNow, elevationNow, pos, mode);

        // 1. 飛んでくる胞子弾(ボスの迫撃砲・増援を運ぶ弾)。エンティティではないので別口で集める。
        //    対地モードは空の目標をまったく見ない(ユーザー指定 2026-09-26)。
        if (RANK_INTERCEPT < rankBelow && mode.engagesAir()) {
            for (ShellTracker.Incoming incoming : ShellTracker.get(level).sporeShellsNear(level, trunnion, range)) {
                candidates.add(new Candidate(RANK_INTERCEPT,
                        GunTurretClaims.claimedByOther(level, pos, KIND_SHELL, incoming.id()),
                        aim.cost(incoming.pos()), KIND_SHELL, incoming.id(), incoming.pos(), range));
            }
        }

        // 2. エンティティ。ヴェロフィルだけ射程が長い(searchRange)ので、クラス索引で別に引く。
        //    それ以外の相手は狙点(当たり判定の中心)が range 以内でなければ canEngage を通らないので、
        //    横と上は range+1 の箱で足りる。下だけは元の箱と同じ searchRange まで取る
        //    (エンティティは足元の区画で引かれるため。背の高い相手 —— 苗床 scale3 で高さ 19.5、
        //    攻城戦ボス 25 —— は、足元の区画が range+4 より下にあり得る)。
        //    元は searchRange の箱 1 つで全部を引いていた。撃てる相手の顔ぶれは変わらない。
        //    2026-09-24 軽量化(監査 633): 射程の外・仰俯角の可動域の外の相手は候補に入れない(addEntityCandidate)。
        //    どちらも canEngage が必ず false を返す相手で、並べ替えは安定なので、残った候補の順番は前と同じ ——
        //    選ばれる相手は変わらない(引き直しの判定 hasTiedEntities も、撃てる相手どうしの同順位だけを見ることになるが、
        //    撃てない相手が絡む同順位はどちらの並びでも「先頭の撃てる相手」を変えない)。
        AABB box = new AABB(trunnion, trunnion).inflate(searchRange);
        if (RANK_INTERCEPT < rankBelow && mode.engagesAir()) {
            for (VelophyllEntity entity : level.getEntitiesOfClass(VelophyllEntity.class, box, GunTurretTargeting::isEnemy)) {
                addEntityCandidate(candidates, entity, aim, rankBelow);
            }
        }
        AABB nearBox = nearSearchBox(trunnion, range, searchRange);
        for (Entity entity : level.getEntities((Entity) null, nearBox,
                e -> !(e instanceof VelophyllEntity) && isEnemy(e) && rank(e) < rankBelow && wants(mode, e))) {
            addEntityCandidate(candidates, entity, aim, rankBelow);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(ORDER);
        if (hasTiedEntities(candidates)) {
            // 優先度も手間もぴったり同じヴェロフィルと爆弾の組があると、2 回に分けて引いた並びのせいで
            // どちらを先に撃つかが元の 1 回引きと入れ替わり得る。そのときだけ元の箱 1 つで引き直す。
            candidates.removeIf(candidate -> candidate.kind() == KIND_ENTITY);
            for (Entity entity : level.getEntities((Entity) null, box,
                    e -> isEnemy(e) && wants(mode, e))) {
                addEntityCandidate(candidates, entity, aim, rankBelow);
            }
            candidates.sort(ORDER);
        }

        for (Candidate candidate : candidates) {
            if (canEngage(level, pos, azimuthNow, candidate.point(), candidate.range())) {
                return new Target(candidate.kind(), candidate.id(), candidate.point());
            }
        }
        return null;
    }

    /**
     * エンティティ 1 体ぶんの候補を足す。ただし射程の外・仰俯角の可動域の外・探していない優先度なら足さない
     * ({@link #canEngage} の最初の 2 つと同じ式 —— 遮蔽の clip を待たずに必ず false になる相手)。
     */
    private static void addEntityCandidate(List<Candidate> candidates, Entity entity, Aim aim, int rankBelow) {
        int rank = rank(entity);
        if (rank >= rankBelow || !wants(aim.mode, entity)) {
            return;
        }
        Vec3 point = aimPoint(entity, 1.0F);
        double distanceSq = point.distanceToSqr(aim.trunnion);
        double range = rangeFor(entity);
        if (outOfReach(aim.trunnion, point, distanceSq, range)) {
            return;
        }
        candidates.add(new Candidate(rank, aim.claimedByOther(entity.getId()), aim.cost(point),
                KIND_ENTITY, entity.getId(), point, range));
    }

    /** いまの銃身の向きと、この砲台自身(候補ごとの {@link #engageCost} と札の判定に使う)。 */
    private record Aim(ServerLevel level, Vec3 trunnion, double azimuth, double elevation, BlockPos pos,
                       GunTurretMode mode) {
        double cost(Vec3 point) {
            return engageCost(this.trunnion, point, this.azimuth, this.elevation);
        }

        /** その相手に、ほかの砲台の札が立っているか({@link GunTurretClaims})。 */
        boolean claimedByOther(int entityId) {
            return GunTurretClaims.claimedByOther(this.level, this.pos, KIND_ENTITY, entityId);
        }
    }

    /**
     * 同じ優先度の中での選び方(小さいほど先)= 銃身をその点へ向けるのにかかるおよその tick(旋回と俯仰の遅い方)
     * + 距離 × {@value #DISTANCE_COST}。
     *
     * <p>2026-09-25 ユーザー報告「ずっと目標を切り替え続け、敵が近くにいるのに撃たない」: はじめは近い順だけだった。
     * 群れの中ではいちばん近い相手が選び直し(10 tick)のたびに入れ替わり、台座の旋回(秒間 22.5 度)が追いつく前に
     * 次の相手へ振られて一発も撃てなかった。いまは向いている方の相手から順に撃ち、目標を持っている間は乗り換えない。
     */
    static double engageCost(Vec3 trunnion, Vec3 point, double azimuthNow, double elevationNow) {
        double[] aim = GunTurretGeometry.aimAt(trunnion, point);
        double turn = Math.abs(CannonBallistics.azimuthDelta(azimuthNow, aim[1]));
        double lift = Math.abs(GunTurretGeometry.clampElevation(aim[0]) - elevationNow);
        double ticks = Math.max(turn / DesignData.defense().turretTraverseSpeed(),
                lift / DesignData.defense().turretElevationSpeed());
        return ticks + Math.sqrt(point.distanceToSqr(trunnion)) * DISTANCE_COST;
    }

    /** いまの目標の優先度(相手が消えていれば {@link #RANK_NORMAL})。 */
    public static int rankOf(Level level, int kind, int id) {
        if (kind == KIND_SHELL) {
            return RANK_INTERCEPT;
        }
        Entity entity = kind == KIND_ENTITY ? level.getEntity(id) : null;
        return entity != null ? rank(entity) : RANK_NORMAL;
    }

    /** 射程の外か、仰俯角の可動域の外か({@link #canEngage} の最初の 2 つと同じ式)。 */
    private static boolean outOfReach(Vec3 trunnion, Vec3 point, double distanceSq, double range) {
        return distanceSq > range * range
                || !GunTurretGeometry.elevationInRange(GunTurretGeometry.aimAt(trunnion, point)[0]);
    }

    /** {@link #addEntityCandidate} が候補に入れる相手か(射程と仰俯角だけ。遮蔽は見ない)。 */
    private static boolean withinReach(Entity entity, Vec3 trunnion) {
        Vec3 point = aimPoint(entity, 1.0F);
        return !outOfReach(trunnion, point, point.distanceToSqr(trunnion), rangeFor(entity));
    }

    /**
     * {@link #pick} でヴェロフィル以外のエンティティを引く箱。横と上は range+1、下だけ searchRange
     * (理由は pick の 2. のコメント)。{@link #interceptInRange} も同じ箱で爆弾を引くので、ここにまとめた。
     */
    private static AABB nearSearchBox(Vec3 trunnion, double range, double searchRange) {
        double near = Math.min(range + 1.0D, searchRange);
        return new AABB(trunnion.x - near, trunnion.y - searchRange, trunnion.z - near,
                trunnion.x + near, trunnion.y + near, trunnion.z + near);
    }

    /**
     * 射程の中に<b>最優先で落とす相手</b>({@link #RANK_INTERCEPT})がいるか —— 飛んでくる胞子弾、
     * 落下中の胞子爆弾({@link VelophyllBombEntity}: ヴェロフィルの爆撃とスカトゥリウムの迫撃砲)、ヴェロフィル。
     * 目標の無い砲台は、これが真のあいだだけ前と同じく毎 tick 探す(2026-09-24 軽量化、監査 87)。
     *
     * <p>はじめは胞子弾だけを見ていたが、レビューの指摘で爆弾とヴェロフィルも足した: スカトゥリウムの迫撃砲は
     * 砲台の射程(当時 32、2026-10-02 から 48)の外の 40 ブロックから撃たれたので、弾が来るとき砲台はたいてい待機中。気づくのが数 tick 遅れると、
     * 空中で割れるか・どこで割れるか(= 灯りや砲台が覆われるか、その乱数を引くか)が変わってしまう。
     *
     * <p>引き方は {@link #pick} と同じ(胞子弾は同じ半径、爆弾は同じ {@link #nearSearchBox}、ヴェロフィルは同じ
     * searchRange の箱)で、ふるいも {@link #addEntityCandidate} と同じ(射程と仰俯角)。遮蔽の clip だけはしない ——
     * 「pick が最優先の相手を選び得る tick」を必ず含む(外れるなら探す側に外れる)ように。
     * クラスで引く索引は安いので、普通の敵を拾う {@code getEntities(nearBox)} の全件走査・並べ替え・遮られた候補の clip は
     * 待機中の 4 tick のうち 3 tick で省けたまま。
     */
    public static boolean interceptInRange(ServerLevel level, BlockPos pos, double azimuthNow, GunTurretMode mode) {
        if (!mode.engagesAir()) {
            // 対地モードは空の目標を見ないので、急いで選び直す理由が無い(2026-09-26)。
            return false;
        }
        Vec3 trunnion = GunTurretGeometry.trunnion(pos, azimuthNow);
        double range = DesignData.defense().turretRange();
        double searchRange = Math.max(range, DesignData.defense().turretVelophyllRange());
        if (!ShellTracker.get(level).sporeShellsNear(level, trunnion, range).isEmpty()) {
            return true;
        }
        return anyInReach(level, VelophyllBombEntity.class, nearSearchBox(trunnion, range, searchRange), trunnion)
                || anyInReach(level, VelophyllEntity.class, new AABB(trunnion, trunnion).inflate(searchRange), trunnion);
    }

    /** その箱に、撃ってよくて射程と仰俯角に入っている {@code type} が 1 体でもいるか(見つけたら打ち切る)。 */
    private static <T extends Entity> boolean anyInReach(ServerLevel level, Class<T> type, AABB box, Vec3 trunnion) {
        List<T> found = new ArrayList<>(1);
        level.getEntities(EntityTypeTest.forClass(type), box,
                entity -> isEnemy(entity) && withinReach(entity, trunnion), found, 1);
        return !found.isEmpty();
    }

    /**
     * 並べ替えた候補に、優先度も距離もぴったり同じ<b>最優先の</b>エンティティの組があるか。
     * 胞子弾は必ずエンティティより先に入れるので、同順位のエンティティどうしは隣り合う。
     *
     * <p>元の 1 回引きと並びが食い違い得るのは、別々に引いたヴェロフィル(1 回目)と落下中の爆弾(2 回目)が
     * 同順位で並ぶときだけで、どちらも {@link #RANK_INTERCEPT}。普通の敵({@link #RANK_NORMAL})は
     * どれも 2 回目の同じ引き方から来るので、区画の順・区画の中の登録順が元のまま —— 引き直さない
     * (穴に落ちた敵が同じ座標に重なる罠の前では、毎 tick 3 回引きになって元より重くなるため)。
     */
    private static boolean hasTiedEntities(List<Candidate> sorted) {
        for (int i = 1; i < sorted.size(); i++) {
            Candidate a = sorted.get(i - 1);
            Candidate b = sorted.get(i);
            if (a.kind() == KIND_ENTITY && b.kind() == KIND_ENTITY && a.rank() == RANK_INTERCEPT
                    && ORDER.compare(a, b) == 0) {
                return true;
            }
        }
        return false;
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
        BlockHitResult hit = clipTo(level, from, point);
        if (hit == null) {
            return null;
        }
        // 当たったのが<b>自分のブロック</b>かどうかは {@link BlockHitResult#getBlockPos} で見る。
        // 当たった点の座標から {@code BlockPos.containing} で出してはいけない(2026-09-26 に GameTest が発見)——
        // 面の上で当たると座標はちょうど境目(例 y = 3.0)になり、floor が<b>隣のマス</b>を指す。
        // 砲耳は台座の上に出ているので、近くて低い相手を狙う線は自分のブロックの<b>上面</b>をかすめる:
        // それを「遮られている」と誤って数えていたため、2 マス先のクモを撃てなかった。
        if (!hit.getBlockPos().equals(pos)) {
            return hit.getLocation();
        }
        // 自分のブロックに当たった。1 マスぶん先へ出てから引き直す。
        Vec3 path = point.subtract(from);
        double length = path.length();
        if (length <= SELF_SKIP) {
            return null;
        }
        BlockHitResult second = clipTo(level, from.add(path.scale(SELF_SKIP / length)), point);
        return second == null ? null : second.getLocation();
    }

    /** {@code from} → {@code to} で最初に当たるブロック(当たらなければ null)。 */
    @Nullable
    private static BlockHitResult clipTo(Level level, Vec3 from, Vec3 to) {
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
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
        if (entity instanceof BlightFernEntity) {
            return false;
        }
        // 地面に潜伏しているヴォラムネス: 傷を受けず Mob からも見つからない姿なので弾の無駄(2026-10-09、仮決め)。
        if (entity instanceof VoramnesEntity worm && worm.isHiddenInGround()) {
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
     * <b>空の目標</b>か(ユーザー要望 2026-09-26 の射撃モード)。飛んでくる胞子弾は常に空の目標なので、
     * ここが見るのはエンティティだけ。
     *
     * <ul>
     *   <li>落下中の胞子爆弾({@link VelophyllBombEntity}: ヴェロフィルの爆撃・スカトゥリウムの迫撃砲)</li>
     *   <li>この MOD の飛ぶ Mob({@link OvergrownFlyerEntity} —— ヴェロフィル・メガネウラなど)</li>
     *   <li>バニラの飛ぶ Mob({@link FlyingMob} —— ガスト・ファントムなど)</li>
     * </ul>
     * 跳ねただけの地上 Mob を空と見なさないよう、<b>種類で決める</b>(その瞬間に接地しているかでは見ない)。
     */
    public static boolean isAirTarget(Entity entity) {
        return entity instanceof VelophyllBombEntity
                || entity instanceof OvergrownFlyerEntity
                || entity instanceof FlyingMob;
    }

    /** そのモードがその相手を狙うか(防空 = 空だけ / 対地 = 地上だけ / 両用 = どちらも)。 */
    public static boolean wants(GunTurretMode mode, Entity entity) {
        return isAirTarget(entity) ? mode.engagesAir() : mode.engagesGround();
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
