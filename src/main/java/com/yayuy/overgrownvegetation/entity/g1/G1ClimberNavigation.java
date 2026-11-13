package com.yayuy.overgrownvegetation.entity.g1;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;

import javax.annotation.Nullable;

/**
 * 壁を登る G1(クモの 1 段目)の navigation(2026-11-13、能力の第 4 回)。バニラの {@code WallClimberNavigation} と同じ作り:
 * 経路の終わりまで歩いたら、行き先のマスへ向けてまっすぐ押す(壁に当たると登る = 壁の上の相手へ届く)。
 *
 * <p>バニラのものをそのまま使わない理由(記憶 wallclimber-nav-hidden-beeline): 行き先 {@code pathToPosition} が private で、
 * {@link #stop()} でも消えず、標的を諦めた後もそこへ押し続ける。ここでは {@link #stop()} で行き先も消す。
 * {@code moveTo(Entity)} は経路が無いとき、バニラと同じく行き先だけ覚えて true を返す(壁越しの相手へ押して登るため)。
 * 届かない相手は {@code G1StrikeGoal} が 100 tick で諦めて {@link #stop()} を呼ぶ。
 */
public class G1ClimberNavigation extends GroundPathNavigation {

    @Nullable
    private BlockPos pathToPosition;

    public G1ClimberNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    @Override
    public Path createPath(BlockPos pos, int accuracy) {
        this.pathToPosition = pos;
        return super.createPath(pos, accuracy);
    }

    @Override
    public Path createPath(Entity entity, int accuracy) {
        this.pathToPosition = entity.blockPosition();
        return super.createPath(entity, accuracy);
    }

    @Override
    public boolean moveTo(Entity entity, double speed) {
        Path path = this.createPath(entity, 0);
        if (path != null) {
            return this.moveTo(path, speed);
        }
        this.pathToPosition = entity.blockPosition();
        this.speedModifier = speed;
        return true;
    }

    /**
     * 経路が引けなかったとき({@code path == null})も速さを覚える。バニラは覚えないので、行き先へ押すときの速さが前の値(最初は 0)のままになり、
     * 経路が一度も引けていない Mob はその場から動かない(2026-11-13、クモの待ち伏せのテストで踏んだ)。
     */
    @Override
    public boolean moveTo(@Nullable Path path, double speed) {
        if (path == null) {
            this.speedModifier = speed;
        }
        return super.moveTo(path, speed);
    }

    /** 行き先も消す(バニラは経路だけ消す)。 */
    @Override
    public void stop() {
        super.stop();
        this.pathToPosition = null;
    }

    @Override
    public void tick() {
        if (!this.isDone()) {
            super.tick();
            return;
        }
        if (this.pathToPosition == null) return;
        double near = Math.max(this.mob.getBbWidth(), 1.0D);
        if (!this.pathToPosition.closerToCenterThan(this.mob.position(), near)
                && (!(this.mob.getY() > this.pathToPosition.getY())
                || !BlockPos.containing(this.pathToPosition.getX(), this.mob.getY(), this.pathToPosition.getZ())
                .closerToCenterThan(this.mob.position(), near))) {
            this.mob.getMoveControl().setWantedPosition(this.pathToPosition.getX(), this.pathToPosition.getY(), this.pathToPosition.getZ(),
                    this.speedModifier > 0.0D ? this.speedModifier : 1.0D);
        } else {
            this.pathToPosition = null;
        }
    }
}
