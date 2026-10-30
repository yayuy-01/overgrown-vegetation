package com.yayuy.overgrownvegetation.td;

import com.yayuy.overgrownvegetation.swarm.goal.SwarmAttacker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * 橋の大工(TD のアルボラ、2026-09-25)。{@link TdBridges} に大工として割り当てられている間、まだ済んでいない最初の段へ寄り、
 * 手が届けば {@value TdBridges#BUILD_INTERVAL_TICKS} tick に 1 回、過繁茂したブロックを置く(頭の上を塞ぐ葉・幹は叩いて崩す)。
 *
 * <p>優先度 {@value #PRIORITY}(MOVE・LOOK): 群れの囲む Goal(3)・先導と後続(4)・護衛の Goal(5)より先に動く。詰まりの立て直し・
 * 坑道(1)には譲る。割り当ては {@link TdBridges} が集めたとき・外したときに変わり、この Goal はそれを毎 tick 見るだけ。
 * Goal は保存されないので、割り当てるたびに差し込む({@link #install}。入っていれば何もしない)。
 */
public final class TdBridgeGoal extends Goal {

    public static final int PRIORITY = 2;
    private static final double SPEED = 1.0D;
    /** 経路を引き直す間隔(tick)。 */
    private static final int REPATH_TICKS = 20;
    /** 段がこれより近ければ(水平、ブロック)経路を引かずにまっすぐ寄る。 */
    private static final double STEER_DISTANCE = 3.0D;

    private final PathfinderMob mob;
    private int cooldown;
    private int repathAt;

    public TdBridgeGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /** まだ入っていなければ {@code mob} に差し込む。 */
    public static void install(PathfinderMob mob) {
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof TdBridgeGoal) {
                return;
            }
        }
        mob.goalSelector.addGoal(PRIORITY, new TdBridgeGoal(mob));
    }

    @Override
    public boolean canUse() {
        return TdBridges.isBuilder(this.mob);
    }

    @Override
    public boolean canContinueToUse() {
        return TdBridges.isBuilder(this.mob);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.cooldown = Math.floorMod(this.mob.getId(), TdBridges.BUILD_INTERVAL_TICKS) + 1;
        this.repathAt = 0;
    }

    @Override
    public void stop() {
        this.mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (!(this.mob.level() instanceof ServerLevel level)) {
            return;
        }
        TdBridges.Task task = TdBridges.task(this.mob);
        if (task == null) {
            return;
        }
        BlockPos pos = task.pos();
        Vec3 centre = Vec3.atCenterOf(pos);
        this.mob.getLookControl().setLookAt(centre.x, centre.y, centre.z, 30.0F, 30.0F);
        if (TdBridges.canReach(this.mob, pos)) {
            if (!this.mob.getNavigation().isDone()) {
                this.mob.getNavigation().stop();
            }
            if (--this.cooldown > 0) {
                return;
            }
            this.cooldown = TdBridges.BUILD_INTERVAL_TICKS;
            if (TdBridges.act(level, this.mob, task)) {
                this.mob.swing(InteractionHand.MAIN_HAND);
                if (this.mob instanceof SwarmAttacker attacker) {
                    attacker.playAttackAnimation();
                }
            }
            return;
        }
        double hx = centre.x - this.mob.getX();
        double hz = centre.z - this.mob.getZ();
        if (hx * hx + hz * hz <= STEER_DISTANCE * STEER_DISTANCE && Math.abs(centre.y - this.mob.getY()) <= 3.0D) {
            // 近い: まっすぐ寄る(宙に置く段へは経路が引けない)。
            if (!this.mob.getNavigation().isDone()) {
                this.mob.getNavigation().stop();
            }
            this.mob.getMoveControl().setWantedPosition(centre.x, this.mob.getY(), centre.z, SPEED);
        } else if (this.mob.tickCount >= this.repathAt) {
            // 間隔だけで引き直す(「経路が終わったら」も見ると、届かない段の前で毎 tick 探索が走る。群れ v2 の罠)。
            this.repathAt = this.mob.tickCount + REPATH_TICKS;
            this.mob.getNavigation().moveTo(centre.x, pos.getY() + 1.0D, centre.z, SPEED);
        }
    }
}
