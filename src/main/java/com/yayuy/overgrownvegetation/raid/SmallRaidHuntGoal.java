package com.yayuy.overgrownvegetation.raid;

import com.yayuy.overgrownvegetation.entity.AbstractOvergrownInvaderEntity;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.UUID;

/**
 * 小規模レイドの一団が、狙いのプレイヤー({@link ModAttachments#HUNT_TARGET})を目指して歩く Goal(2026-09-19)。
 * 小規模レイドは 2026-10-10 に削除(古いセーブの一団を片付けるために残している。{@link SmallRaidHunt} 参照)。
 * {@code entity/GuidedHuntInjector} がグループ1〜10 の全 {@link PathfinderMob} に仕込む。
 *
 * <p>作りは {@code entity/GuidedHuntGoal}(フィロセンスの誘導)と同じ — 遠すぎる相手は
 * {@code setTarget} しても次の tick で外れるので、<b>自分で歩いて</b> {@link #ENGAGE_DISTANCE} まで寄ってから
 * 通常の戦闘 Goal へ渡す。戦闘で相手を見失ったら(標的が消えたら)またこの Goal が拾って歩き出す。
 *
 * <p>狙いが外れる条件: プレイヤーが死んだ / 別のワールドへ移った / ログアウトした /
 * クリエイティブ・スペクテイターになった / 経路が続けて取れない(届かない)/ 期限切れ。規則は {@link SmallRaidHunt}
 * (2026-09-25、届かない・期限切れを追加)。いずれも狙いを消して、その場の普通の行動に戻る
 * (湧いたときに付けた永続も外れ、普段の規則で消えるようになる。2026-09-24、{@link EventMobs})。
 */
public class SmallRaidHuntGoal extends Goal {

    private static final double ENGAGE_DISTANCE = 20.0D;
    private static final int REPATH_INTERVAL = 20;
    private static final double WAYPOINT_STEP = 24.0D;

    private final PathfinderMob mob;
    private final double speedModifier;

    private Player quarry;
    private int repathTimer;
    /** 見失っている回数({@link SmallRaidHunt#LOST_GRACE_TICKS} を超えたら狙いを消す)。 */
    private int lostTicks;
    /** 続けて失敗した区間の数({@link SmallRaidHunt#UNREACHABLE_LEGS} に達したら狙いを消す)。保存しない。 */
    private int failedLegs;
    /** 今の区間を始めた(前回経路を引き直した)ときの位置。Goal を始め直したら null(最初の区間は動きで判定しない)。 */
    @Nullable
    private Vec3 legStart;
    /**
     * 最後に UUID として読めた {@link ModAttachments#HUNT_TARGET} の文字列(参照そのもの)と、その UUID。
     * canUse のたびに同じ文字列を解析し直さないため(2026-09-24 軽量化、報告 1989)。
     */
    @Nullable
    private String parsedRaw;
    @Nullable
    private UUID parsedId;

    public SmallRaidHuntGoal(PathfinderMob mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /** その Mob が小規模レイドの狙いを持っているか({@link SmallRaidHunt#hasHunt} と同じ)。 */
    public static boolean hasHunt(LivingEntity entity) {
        return SmallRaidHunt.hasHunt(entity);
    }

    @Nullable
    private Player resolveQuarry(ServerLevel level) {
        String raw = this.mob.getExistingData(ModAttachments.HUNT_TARGET).orElse("");
        if (raw.isEmpty()) {
            return null;
        }
        UUID id;
        if (raw == this.parsedRaw) {
            // 前回読んだのと同じ文字列(attachment が書き換わっていない)。読み直しても同じ UUID(2026-09-24 軽量化、報告 1989)。
            id = this.parsedId;
        } else {
            id = SmallRaidHunt.parseTarget(raw);
            if (id == null) {
                clearHunt();
                return null;
            }
            this.parsedRaw = raw;
            this.parsedId = id;
        }
        Player player = level.getPlayerByUUID(id);
        if (player == null) {
            // 別ワールド or ログアウト。しばらく待って戻らなければ諦める。
            this.lostTicks++;
        } else {
            this.lostTicks = 0;
        }
        // 見失いすぎ・もう狙えない・届かない・期限切れ(2026-09-25)のどれかなら狙いを消す。
        if (SmallRaidHunt.shouldGiveUp(this.mob, level.getGameTime(), player, this.lostTicks, this.failedLegs)) {
            clearHunt();
            return null;
        }
        return player;
    }

    private void clearHunt() {
        // 小規模レイドはこの個体にとってここで終わり。湧いたときに付けた永続も外れる(2026-09-24、EventMobs)。
        SmallRaidHunt.giveUp(this.mob);
        this.quarry = null;
        this.lostTicks = 0;
        this.failedLegs = 0;
        this.legStart = null;
    }

    @Override
    public boolean canUse() {
        if (!(this.mob.level() instanceof ServerLevel level) || !hasHunt(this.mob)) {
            return false;
        }
        // G4 の群れの一員は動かない(群れ v2、2026-09-25)。狙いへ向かうのも、見失い・期限・届かないで諦めるのも
        // 群れの司令塔が群れ単位で行う(swarm/SwarmTargeting。規則はここと同じ SmallRaidHunt)。
        if (com.yayuy.overgrownvegetation.swarm.SwarmMember.isInSwarm(this.mob)) {
            return false;
        }
        // 期限切れは、戦闘中・行動中で下の判定まで来ない間にも見る(2026-09-25、報告 1978。ずっと何かと戦っている個体も
        // 期限で狙いと永続を手放すように)。印の時刻を読むだけなので安い。
        if (SmallRaidHunt.expired(this.mob, level.getGameTime())) {
            clearHunt();
            return false;
        }
        if (busyWithOwnAction()) {
            return false;
        }
        // すでに何かと戦っているなら、そちらを優先させる(近くの村人・家畜も襲う)。
        LivingEntity current = this.mob.getTarget();
        if (current != null && current.isAlive() && this.mob.distanceTo(current) < ENGAGE_DISTANCE) {
            return false;
        }
        this.quarry = resolveQuarry(level);
        if (this.quarry == null) {
            return false;
        }
        // 標的を掴めない種(スポロフィル)も、寄り切ったらそこで打ち切って普段の Goal(群れ・前進)へ渡す
        // (2026-09-24、GuidedHuntGoal と同じ)。以前は {@link #ENGAGE_DISTANCE} の内側でも発動し続け、
        // tick で降りては次の判定でまた発動するのを繰り返して、そのたびに群れ・前進の Goal の
        // 経路探索が走っては潰され、プレイヤーの手前で立ち尽くしていた。
        if (!canEngage() && this.mob.distanceTo(this.quarry) <= ENGAGE_DISTANCE) {
            this.quarry = null;
            return false;
        }
        return this.mob.distanceTo(this.quarry) > ENGAGE_DISTANCE;
    }

    private boolean canEngage() {
        return !(this.mob instanceof AbstractOvergrownInvaderEntity invader) || invader.useTargetGoals();
    }

    private boolean busyWithOwnAction() {
        return this.mob instanceof AbstractOvergrownInvaderEntity invader
                && invader.getActionState() != AbstractOvergrownInvaderEntity.STATE_IDLE;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.quarry == null || !this.quarry.isAlive() || !hasHunt(this.mob)) {
            return false;
        }
        LivingEntity current = this.mob.getTarget();
        if (current != null && current.isAlive() && current != this.quarry
                && this.mob.distanceTo(current) < ENGAGE_DISTANCE) {
            return false;
        }
        // 途中で群れに入った。狙いの寿命も群れの司令塔が持つ。
        return !busyWithOwnAction() && !com.yayuy.overgrownvegetation.swarm.SwarmMember.isInSwarm(this.mob);
    }

    @Override
    public void start() {
        // 最初の引き直しを個体ごとに 1〜4 回目の tick() へ散らす(2026-09-24 軽量化、報告 299。GuidedHuntGoal と同じ)。
        // 一団は同じ tick に湧いて同じ tick に動き出すので、0 のままだと全員の経路探索が同じ tick に揃い続けていた。
        this.repathTimer = 1 + Math.floorMod(this.mob.getId(), 4);
        // 戦闘などで間が空いたあとの最初の区間は、動いた量では判定しない(失敗の数え続けは持ち越す)。
        this.legStart = null;
    }

    @Override
    public void stop() {
        this.quarry = null;
        this.mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (this.quarry == null) {
            return;
        }
        this.mob.getLookControl().setLookAt(this.quarry, 20.0F, 20.0F);
        if (this.mob.distanceTo(this.quarry) <= ENGAGE_DISTANCE) {
            if (canEngage()) {
                this.mob.setTarget(this.quarry);
            }
            this.quarry = null;
            this.failedLegs = 0; // 寄り切れた = 届いた
            return;
        }
        if (--this.repathTimer <= 0) {
            // 間隔も個体ごとに ±2 ずらし、揃った位相が自然にほどけるようにする(2026-09-24 軽量化、報告 299)。
            this.repathTimer = REPATH_INTERVAL - 2 + Math.floorMod(this.mob.getId(), 5);
            boolean gotPath = moveToward(this.quarry.position());
            // 区間の判定(2026-09-25、報告 1130): 経路が取れない、または前の区間でほとんど動けなかったら失敗。
            // 続けて SmallRaidHunt.UNREACHABLE_LEGS 回失敗したら、届かない相手として狙いを捨てる。
            Vec3 here = this.mob.position();
            double movedSq = -1.0D;
            if (this.legStart != null) {
                double dx = here.x - this.legStart.x;
                double dz = here.z - this.legStart.z;
                movedSq = dx * dx + dz * dz;
            }
            this.legStart = here;
            if (!SmallRaidHunt.legFailed(gotPath, movedSq)) {
                this.failedLegs = 0;
            } else if (SmallRaidHunt.unreachable(++this.failedLegs)) {
                clearHunt(); // 次の canContinueToUse で降りる(狙いが空になるので)
            }
        }
    }

    /** 中継点(遠ければ {@link #WAYPOINT_STEP} 先)へ経路を引く。経路が取れたら true。 */
    private boolean moveToward(Vec3 destination) {
        Vec3 here = this.mob.position();
        Vec3 delta = destination.subtract(here);
        double distance = delta.length();
        Vec3 goal = destination;
        if (distance > WAYPOINT_STEP) {
            goal = here.add(delta.scale(WAYPOINT_STEP / distance));
        }
        if (this.mob.getNavigation().moveTo(goal.x, goal.y, goal.z, this.speedModifier)) {
            return true;
        }
        // 中継点 = 目的地のときの 2 回目は、同じ tick・同じ位置からの同じ探索なので呼ばない
        // (2026-09-24 軽量化、報告 792。理由と拡大種だけ呼び続けるわけは GuidedHuntGoal#moveToward と同じ)。
        if (goal != destination || this.mob instanceof AbstractOvergrownInvaderEntity) {
            return this.mob.getNavigation().moveTo(destination.x, destination.y, destination.z, this.speedModifier);
        }
        return false;
    }
}
