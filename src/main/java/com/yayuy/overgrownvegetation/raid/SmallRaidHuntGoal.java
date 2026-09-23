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
 * {@code entity/GuidedHuntInjector} がグループ1〜10 の全 {@link PathfinderMob} に仕込む。
 *
 * <p>作りは {@code entity/GuidedHuntGoal}(フィロセンスの誘導)と同じ — 遠すぎる相手は
 * {@code setTarget} しても次の tick で外れるので、<b>自分で歩いて</b> {@link #ENGAGE_DISTANCE} まで寄ってから
 * 通常の戦闘 Goal へ渡す。戦闘で相手を見失ったら(標的が消えたら)またこの Goal が拾って歩き出す。
 *
 * <p>狙いが外れる条件: プレイヤーが死んだ / 別のワールドへ移った / ログアウトした /
 * クリエイティブ・スペクテイターになった。いずれも狙いを消して、その場の普通の行動に戻る。
 */
public class SmallRaidHuntGoal extends Goal {

    private static final double ENGAGE_DISTANCE = 20.0D;
    private static final int REPATH_INTERVAL = 20;
    private static final double WAYPOINT_STEP = 24.0D;
    /** 見失ったプレイヤーを待つ tick(ログアウトなどで戻らなければ狙いを消す)。 */
    private static final int LOST_GRACE_TICKS = 1200;

    private final PathfinderMob mob;
    private final double speedModifier;

    private Player quarry;
    private int repathTimer;
    private int lostTicks;

    public SmallRaidHuntGoal(PathfinderMob mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /** その Mob が小規模レイドの狙いを持っているか。 */
    public static boolean hasHunt(LivingEntity entity) {
        String raw = entity.getExistingData(ModAttachments.HUNT_TARGET).orElse("");
        return !raw.isEmpty();
    }

    @Nullable
    private Player resolveQuarry(ServerLevel level) {
        String raw = this.mob.getExistingData(ModAttachments.HUNT_TARGET).orElse("");
        if (raw.isEmpty()) {
            return null;
        }
        UUID id;
        try {
            id = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            clearHunt();
            return null;
        }
        Player player = level.getPlayerByUUID(id);
        if (player == null) {
            // 別ワールド or ログアウト。しばらく待って戻らなければ諦める。
            if (++this.lostTicks > LOST_GRACE_TICKS) {
                clearHunt();
            }
            return null;
        }
        this.lostTicks = 0;
        if (!player.isAlive() || player.isSpectator() || player.isCreative()) {
            clearHunt();
            return null;
        }
        return player;
    }

    private void clearHunt() {
        this.mob.setData(ModAttachments.HUNT_TARGET, "");
    }

    @Override
    public boolean canUse() {
        if (!(this.mob.level() instanceof ServerLevel level) || !hasHunt(this.mob)) {
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
        return this.mob.distanceTo(this.quarry) > ENGAGE_DISTANCE || !canEngage();
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
        return !busyWithOwnAction();
    }

    @Override
    public void start() {
        this.repathTimer = 0;
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
            return;
        }
        if (--this.repathTimer <= 0) {
            this.repathTimer = REPATH_INTERVAL;
            moveToward(this.quarry.position());
        }
    }

    private void moveToward(Vec3 destination) {
        Vec3 here = this.mob.position();
        Vec3 delta = destination.subtract(here);
        double distance = delta.length();
        Vec3 goal = destination;
        if (distance > WAYPOINT_STEP) {
            goal = here.add(delta.scale(WAYPOINT_STEP / distance));
        }
        if (!this.mob.getNavigation().moveTo(goal.x, goal.y, goal.z, this.speedModifier)) {
            this.mob.getNavigation().moveTo(destination.x, destination.y, destination.z, this.speedModifier);
        }
    }
}
