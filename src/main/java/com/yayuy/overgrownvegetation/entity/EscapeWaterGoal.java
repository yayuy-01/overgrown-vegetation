package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.util.ChunkCachedReader;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;

/**
 * 水に落ちた陸の Mob が<b>いちばん近い陸へ急いで上がる</b> Goal(ユーザー要望 2026-09-17)。
 * 差し込みは {@link EscapeWaterInjector}(グループ1〜10 の陸生 Mob 全部)。
 *
 * <p>狙いは「水ぐされ({@link com.yayuy.overgrownvegetation.effect.WaterRotTracker})で溶けるまで
 * 水の中をうろうろしない」こと。発症まで 30 秒あるので、まっすぐ岸へ向かえばまず助かる。
 *
 * <h2>経路探索は使わない</h2>
 * 陸生の過繁茂 Mob は {@code PathType.WATER} の malus が -1(= 水に入らない)なので、
 * <b>水の中からでは経路が引けない</b> —— 出発点が「入ってはいけない場所」になってしまう。
 * そこでこの Goal は経路を引かず、{@link net.minecraft.world.entity.ai.control.MoveControl} に
 * 毎 tick 行き先を渡して直接泳がせる(経路探索は {@link #start()} で止める)。
 * 浮き上がりはバニラの {@link net.minecraft.world.entity.ai.goal.FloatGoal}(優先度 0・JUMP)が
 * やるので、こちらは MOVE と LOOK しか取らない。
 *
 * <h2>どの岸を選ぶか</h2>
 * 半径 {@value #SEARCH_RADIUS} の中の「足場があって水に浸かっていない」マスのうち<b>いちばん近いもの</b>。
 * ただし<b>陸にいる</b>相手を追跡中の場合、相手から遠ざかる岸には {@value #BACKWARD_PENALTY} の下駄を
 * 履かせるので、前方に上がれる岸があるかぎりそちらを選ぶ。これが無いと、川を渡っている最中に手前の岸へ
 * 戻り→追跡で水へ入り直し→また戻り、と汀で往復してしまう。
 *
 * <p>相手が<b>水の中</b>にいる場合はこの下駄を履かせない —— 相手を追って川の奥へ上陸地点を探しに
 * 行ってしまうため。そもそも遠距離攻撃を持たない種は水中の相手を狙わない
 * ({@link Predation#isLandTarget}、2026-09-17)ので、これは反撃({@link OvergrownHurtByTargetGoal})で
 * 水中の相手を標的にしている場合の保険。
 *
 * <p>地形の走査は {@value #RESCAN_INTERVAL} tick に 1 回だけ({@link ChunkCachedReader} 経由)。
 * 見つからなかったときは {@value #FAILED_SCAN_INTERVAL} tick 空ける(大海原のど真ん中で毎 tick
 * 8000 マスを舐めないため)。{@value #MAX_TICKS} tick 泳いでも上がれなければ一度あきらめて、
 * ほかの Goal に譲る(壁に向かって泳ぎ続けて詰むのを避ける)。
 */
public class EscapeWaterGoal extends Goal {

    /** 岸を探す水平半径(マス)。 */
    private static final int SEARCH_RADIUS = 10;
    /** 岸を探す上方向/下方向の範囲(マス)。 */
    private static final int SEARCH_UP = 4;
    private static final int SEARCH_DOWN = 3;
    /** 「急いで」上がる ── 移動速度の倍率。 */
    private static final double SPEED = 1.3D;
    /** 岸を探し直す間隔(tick)。 */
    private static final int RESCAN_INTERVAL = 20;
    /** 岸が見つからなかったときに次を探すまでの間隔(tick)。 */
    private static final int FAILED_SCAN_INTERVAL = 60;
    /** これだけ泳いでも上がれなければ一度あきらめる(tick)。 */
    private static final int MAX_TICKS = 300;
    /** あきらめたあとの待ち(tick)。 */
    private static final int GIVE_UP_COOLDOWN = 100;
    /** 追跡中の相手から遠ざかる岸に履かせる下駄(探索範囲内のどの距離より大きい値)。 */
    private static final double BACKWARD_PENALTY = 4096.0D;

    private final PathfinderMob mob;
    private BlockPos shore;
    private int ticks;
    private int nextScanTick;

    public EscapeWaterGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!this.mob.isInWater() || this.mob.tickCount < this.nextScanTick) {
            return false;
        }
        // 跳び越え({@link InvaderWaterLeapGoal})の最中に水面をかすめただけなら割り込まない。
        // 空中にいて、体が水に浸かっている高さも跳ねる閾値に届いていない = 通過中。
        if (!this.mob.onGround() && this.mob.getFluidHeight(FluidTags.WATER) < this.mob.getFluidJumpThreshold()) {
            return false;
        }
        // 動けない種(汚染シダなど)は探すだけ無駄。
        if (this.mob.getAttributeValue(Attributes.MOVEMENT_SPEED) <= 0.0D) {
            return false;
        }
        this.nextScanTick = this.mob.tickCount + RESCAN_INTERVAL;
        this.shore = findNearestLand();
        if (this.shore == null) {
            this.nextScanTick = this.mob.tickCount + FAILED_SCAN_INTERVAL;
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.mob.isInWater() && this.shore != null && this.ticks <= MAX_TICKS;
    }

    @Override
    public void start() {
        this.ticks = 0;
        // 水の中では経路が引けない(malus -1)。残っている経路は MoveControl を上書きするので捨てる。
        this.mob.getNavigation().stop();
    }

    @Override
    public void stop() {
        if (this.ticks > MAX_TICKS) {
            this.nextScanTick = this.mob.tickCount + GIVE_UP_COOLDOWN;
        }
        this.shore = null;
        this.ticks = 0;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        this.ticks++;
        if (this.mob.tickCount >= this.nextScanTick) {
            this.nextScanTick = this.mob.tickCount + RESCAN_INTERVAL;
            BlockPos found = findNearestLand();
            if (found != null) {
                this.shore = found;
            }
        }
        double x = this.shore.getX() + 0.5D;
        double y = this.shore.getY();
        double z = this.shore.getZ() + 0.5D;
        this.mob.getLookControl().setLookAt(x, y + this.mob.getEyeHeight(), z);
        // MoveControl は 1 tick で WAIT に戻るので毎 tick 渡し直す。壁(岸の段差)は MoveControl が
        // 自分で跳ねて越える。
        this.mob.getMoveControl().setWantedPosition(x, y, z, SPEED);
    }

    /** 半径 {@value #SEARCH_RADIUS} の中で上がれる陸のうち、いちばん「近い」もの。無ければ {@code null}。 */
    private BlockPos findNearestLand() {
        BlockPos origin = this.mob.blockPosition();
        LivingEntity target = this.mob.getTarget();
        if (target != null && AquaticOvergrownMob.inWaterOrAtSurface(target)) {
            // 水の中の相手に引っぱられると、岸ではなく川の奥を選んでしまう。
            target = null;
        }
        double targetDistSq = target == null ? 0.0D : target.distanceToSqr(this.mob);
        ChunkCachedReader reader = new ChunkCachedReader(this.mob.level());
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                for (int dy = SEARCH_UP; dy >= -SEARCH_DOWN; dy--) {
                    int x = origin.getX() + dx;
                    int y = origin.getY() + dy;
                    int z = origin.getZ() + dz;
                    if (!canStandOn(reader, x, y, z)) {
                        continue;
                    }
                    double score = origin.distToLowCornerSqr(x, y, z);
                    if (target != null && target.distanceToSqr(x + 0.5D, y, z + 0.5D) > targetDistSq) {
                        score += BACKWARD_PENALTY;
                    }
                    if (score < bestScore) {
                        bestScore = score;
                        best = new BlockPos(x, y, z);
                    }
                }
            }
        }
        return best;
    }

    /** そのマスに「水に浸からずに」立てるか(足場がある・体が入る)。 */
    private boolean canStandOn(ChunkCachedReader reader, int x, int y, int z) {
        if (!reader.getFluidState(x, y, z).isEmpty()) {
            // 水面/水中。ここへ上がっても水ぐされは止まらない。
            return false;
        }
        BlockState ground = reader.getBlockState(x, y - 1, z);
        if (!ground.blocksMotion()) {
            return false;
        }
        return !reader.getBlockState(x, y, z).blocksMotion()
                && !reader.getBlockState(x, y + 1, z).blocksMotion();
    }
}
