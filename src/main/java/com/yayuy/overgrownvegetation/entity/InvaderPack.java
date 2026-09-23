package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * <b>グループ4(拡大種)の群れ</b>(ユーザー要望 2026-09-15:
 * 「グループ4の mob 達は基本的にグループ4の mob 同士で集団行動するようにしてほしい」)。
 *
 * <p><b>群れは所属を持つ</b>(2026-09-15 にユーザー要望で追加:
 * 「常に同じ個体の群れを形成し続けるように」)。各個体が群れの ID を憶えていて
 * ({@link InvaderPackBond})、同じ ID を持つ個体の集まりが 1 つの群れ。
 * 入る・作る・合流する・抜ける の判定は {@link InvaderPackBonds}。
 * <b>ワールド側に名簿は作らない</b> —— ID は個体が持ちエンティティと一緒に保存されるので、
 * 死んだ個体を消して回る後始末が要らない。
 *
 * <p>その上で、隊列の作り方は 4 つの仕掛けが受け持つ。
 * 数えるのは<b>同じ群れの仲間だけ</b>({@link #packMatesNear})。
 * <ol>
 *     <li><b>編成をそろえる</b>({@link InvaderPackComposition} + {@link InvaderPackGoal}) —
 *     アルボラ 8 / スポロフィル 2 / フィロセンス 1 / ヴェロフィル 3 / スカトゥリウム 2 に足りない種があるあいだは、
 *     ふだんより広い {@link #recruitRange()} まで見て迎えに行く({@link #nearestRecruit})。
 *     <b>これは最低数であって上限ではない</b> —— 揃ったあとに来た個体もそのまま加わる。</li>
 *     <li><b>離れたら戻る</b>({@link InvaderPackGoal} / {@link VelophyllWanderGoal}) —
 *     仲間の重心から {@link InvaderPackMember#packCohesionDistance()} 以上離れたら歩いて戻る。
 *     役割ごとに距離が違うので、寄り集まると<b>スポロフィルが中央・フィロセンスが後ろ・
 *     ヴェロフィルが上空</b>という並びに自然と落ち着く。</li>
 *     <li><b>同じ方向へ進む</b>({@link InvaderAdvanceGoal} + {@link #sharedDestination}) —
 *     過繁茂バイオームの外へ出るとき、先に動き出した個体の行き先を後続が写す。</li>
 *     <li><b>一緒に襲う</b>({@link #shareTarget}) — 誰かが標的を掴んだら、
 *     手の空いている仲間へ回す。回された側もまた回すので、警報が群れの外側へ波のように伝わる。</li>
 * </ol>
 *
 * <p>フィロセンスの誘導({@link PhyllosensInfluence})との違いは<b>射程と相手</b>。
 * あちらは「グループ1〜10 のすべて」を「何百ブロック先のプレイヤー」へ差し向ける戦略の仕掛けで、
 * こちらは「グループ4 同士」が「目の前の数十ブロック」で崩れずに動くための戦術の仕掛け。
 * 両立する(誘導で集まってきた先で、この群れが形を作る)。
 */
public final class InvaderPack {

    /** {@code entity} が群れの一員か(= 動けるグループ4。汚染シダなどの根を張る種は入らない)。 */
    public static boolean isMember(@Nullable LivingEntity entity) {
        return entity instanceof Mob && entity instanceof InvaderPackMember && entity.isAlive();
    }

    /** 仲間を探す半径(ブロック)。この中に居るのが「いまの群れ」。 */
    public static double scanRange() {
        return OvergrownVegetationConfig.COMMON.invaderPackScanRange.get();
    }

    /** 編成が足りていないときだけ使う、<b>迎えに行く</b>半径(ブロック)。 */
    public static double recruitRange() {
        return OvergrownVegetationConfig.COMMON.invaderPackRecruitRange.get();
    }

    /**
     * <b>編成に足りない種のうち、いちばん近い個体</b>({@code range} 以内)。
     * 居なければ {@code null}。{@link InvaderPackGoal} がここへ迎えに行く。
     *
     * <p>{@code wanted} は {@link InvaderPackComposition#shortfall} の結果。
     * 足りている種は対象外なので、<b>すでに手元に居る仲間を追いかけ直すことはない</b>。
     */
    @Nullable
    public static Mob nearestRecruit(Mob self, Set<EntityType<?>> wanted, double range) {
        if (wanted.isEmpty() || range <= 0.0D) {
            return null;
        }
        AABB box = self.getBoundingBox().inflate(range);
        double rangeSq = range * range;
        Mob best = null;
        double bestSq = Double.MAX_VALUE;
        for (Mob candidate : self.level().getEntitiesOfClass(Mob.class, box,
                mob -> mob != self && isMember(mob) && wanted.contains(mob.getType()))) {
            double sq = self.distanceToSqr(candidate);
            if (sq <= rangeSq && sq < bestSq) {
                bestSq = sq;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * {@code members} のうち<b>いちばん近い先遣隊</b>(アルボラ)。居なければ {@code null}。
     * ほかの種はこれを目印に隊列を組む({@link InvaderPackGoal})。
     */
    @Nullable
    public static Mob nearestPointUnit(Mob self, List<Mob> members) {
        Mob best = null;
        double bestSq = Double.MAX_VALUE;
        for (Mob mob : members) {
            if (!(mob instanceof InvaderPackMember member) || !member.isPackPointUnit()) {
                continue;
            }
            double sq = self.distanceToSqr(mob);
            if (sq < bestSq) {
                bestSq = sq;
                best = mob;
            }
        }
        return best;
    }

    /**
     * {@code members} のうち<b>先遣隊でない個体</b>(= 連れて行く側)までの最大距離。
     * 連れて行く相手が 1 体も居なければ {@code -1}。
     *
     * <p>先遣隊が「後続を置き去りにしていないか」を測るのに使う。
     */
    public static double farthestFollower(Mob self, List<Mob> members) {
        double worst = -1.0D;
        for (Mob mob : members) {
            if (!(mob instanceof InvaderPackMember member) || member.isPackPointUnit()) {
                continue;
            }
            double distance = self.distanceTo(mob);
            if (distance > worst) {
                worst = distance;
            }
        }
        return worst;
    }

    /**
     * {@code self} の周り {@code range} ブロックに居る仲間(自分は含まない)。
     *
     * <p>毎 tick 呼ぶと重い。Goal 側は数十 tick に 1 回だけ数えること。
     */
    public static List<Mob> membersNear(Mob self, double range) {
        AABB box = self.getBoundingBox().inflate(range);
        double rangeSq = range * range;
        List<Mob> members = new ArrayList<>();
        for (Mob candidate : self.level().getEntitiesOfClass(Mob.class, box,
                mob -> mob != self && isMember(mob))) {
            if (self.distanceToSqr(candidate) <= rangeSq) {
                members.add(candidate);
            }
        }
        return members;
    }

    /**
     * {@code self} の周り {@code range} ブロックに居る<b>同じ群れの仲間</b>(自分は含まない)。
     *
     * <p>隊列・重心・行き先・標的の共有は<b>すべてこちらを使う</b> ——
     * 通りすがりの別の群れを自分の群れとして数えないため(ユーザー要望 2026-09-15
     * 「常に同じ個体の群れを形成し続ける」)。
     * 所属の無い個体はどの群れとも一緒に動く({@link InvaderPackBond#shares})ので、
     * まだ群れになっていない個体が置いてきぼりになることはない。
     *
     * <p>逆に、<b>別の群れを探す</b>のは {@link #nearestRecruit} と {@link #membersNear} の仕事。
     * 編成が足りない群れが別の群れを迎えに行き、出会ったところで
     * {@link InvaderPackBonds} が大きいほうへ合流させる。
     */
    public static List<Mob> packMatesNear(Mob self, double range) {
        UUID mine = self instanceof InvaderPackMember member ? member.packBond().id() : null;
        AABB box = self.getBoundingBox().inflate(range);
        double rangeSq = range * range;
        List<Mob> mates = new ArrayList<>();
        for (Mob candidate : self.level().getEntitiesOfClass(Mob.class, box,
                mob -> mob != self && isMember(mob))) {
            UUID theirs = ((InvaderPackMember) candidate).packBond().id();
            if (InvaderPackBond.shares(mine, theirs) && self.distanceToSqr(candidate) <= rangeSq) {
                mates.add(candidate);
            }
        }
        return mates;
    }

    /**
     * 群れの重心。{@code self} も数に入れる({@link InvaderPackMember#countsInPackCentroid()} が
     * true の個体だけ)。誰も数えられなければ {@code null}。
     *
     * <p>自分を含めるのは<b>引き合いを釣り合わせる</b>ため —— 5 体の塊と離れた 1 体では、
     * 重心は塊のすぐ近くに来る。塊の側はほとんど動かず、離れた 1 体がほぼ全部歩く。
     */
    @Nullable
    public static Vec3 centroid(Mob self, List<Mob> members) {
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        int count = 0;
        if (!(self instanceof InvaderPackMember member) || member.countsInPackCentroid()) {
            x = self.getX();
            y = self.getY();
            z = self.getZ();
            count = 1;
        }
        for (Mob mob : members) {
            if (mob instanceof InvaderPackMember other && !other.countsInPackCentroid()) {
                continue;
            }
            x += mob.getX();
            y += mob.getY();
            z += mob.getZ();
            count++;
        }
        return count == 0 ? null : new Vec3(x / count, y / count, z / count);
    }

    /**
     * 仲間がすでに置いている<b>群れ全体の行き先</b>({@link InvaderPackMember#getPackDestination()})。
     * いちばん近い個体のものを使う。誰も向かっていなければ {@code null}。
     */
    @Nullable
    public static Vec3 sharedDestination(Mob self, List<Mob> members) {
        Vec3 best = null;
        double bestSq = Double.MAX_VALUE;
        for (Mob mob : members) {
            if (!(mob instanceof InvaderPackMember member)) {
                continue;
            }
            Vec3 destination = member.getPackDestination();
            if (destination == null) {
                continue;
            }
            double sq = self.distanceToSqr(mob);
            if (sq < bestSq) {
                bestSq = sq;
                best = destination;
            }
        }
        return best;
    }

    /**
     * <b>自分の標的を、手の空いている仲間へ回す</b>。毎 tick 呼んでよい(中で間引く)。
     *
     * <p>回すのは<b>自分が本当に掴んでいる相手</b>だけ({@code FOLLOW_RANGE} 内)。これが無いと、
     * 反撃で掴んだだけの遠い相手や、見失いかけの相手が群れ中に広まってしまう。
     * 受け取るのは<b>標的を持っていない</b>個体だけなので、行き渡ったところで自然に止まる。
     *
     * <p>受け取った側も次の tick に同じことをするので、警報は<b>群れの外側へ波のように伝わる</b>。
     * 1 回に伝わる距離が {@code invaderPackCallRange} に限られているのが歯止めで、
     * 「近くに居た仲間が次々と気づいて集まってくる」という見え方になる。
     *
     * <p>走査の間隔は個体ごとにずらしてある({@code getId()} で位相を振る) —— 同じ tick に
     * 群れ全員が周囲を探すと、そこだけ処理が跳ねる。
     */
    public static void shareTarget(Mob caller) {
        if (!(caller.level() instanceof ServerLevel)) {
            return;
        }
        int interval = OvergrownVegetationConfig.COMMON.invaderPackCallIntervalTicks.get();
        if (interval <= 0 || Math.floorMod(caller.tickCount, interval) != Math.floorMod(caller.getId(), interval)) {
            return;
        }
        LivingEntity target = caller.getTarget();
        if (target == null || !target.isAlive()) {
            return;
        }
        double reach = caller.getAttributeValue(Attributes.FOLLOW_RANGE);
        if (caller.distanceToSqr(target) > reach * reach) {
            return;
        }
        double range = caller instanceof InvaderPackMember member
                ? member.packCallRange()
                : OvergrownVegetationConfig.COMMON.invaderPackCallRange.get();
        if (range <= 0.0D) {
            return;
        }
        for (Mob ally : packMatesNear(caller, range)) {
            if (!canTakeOver(ally, target)) {
                continue;
            }
            ally.setTarget(target);
        }
    }

    /** {@code ally} に {@code target} を渡してよいか。 */
    private static boolean canTakeOver(Mob ally, LivingEntity target) {
        if (ally == target) {
            return false;
        }
        LivingEntity held = ally.getTarget();
        if (held != null && held.isAlive()) {
            // すでに戦っている相手がいる。横から別の相手を押し付けない。
            return false;
        }
        // 味方(グループ1〜10)・水中の相手・自分よりずっと深く潜っている相手は回さない。
        // 索敵 Goal(OvergrownLandTargetGoal)と同じ足切りをここでも通す。
        // 深さを見るのは 2026-09-20 に追加 —— 洞窟の中の相手を回されると、
        // 受け取った側が地表で睨み続ける(アルボラなら坑道を掘り始める)。
        if (!Predation.isInvaderTarget(ally, target)
                || !Predation.isLandTarget(ally, target)
                || !Predation.isWithinChaseDepth(ally, target)) {
            return false;
        }
        if (!ally.canAttack(target)) {
            return false;
        }
        return !(ally instanceof InvaderPackMember member) || member.acceptsPackTarget(target);
    }

    private InvaderPack() {
    }
}
