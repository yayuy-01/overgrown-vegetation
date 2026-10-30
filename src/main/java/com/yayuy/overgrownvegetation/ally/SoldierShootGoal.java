package com.yayuy.overgrownvegetation.ally;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * 兵士の撃ち合い(2026-10-01、仮)。標的が見えて射程の中なら足を止めて構え、{@link SoldierKind#burst} 発ずつ撃つ。
 * 近すぎれば(5 マス未満)下がり、見えない・遠いなら寄る。砲兵は離れた標的へ迫撃砲も撃つ(撃てる間は小銃より先)。
 */
public class SoldierShootGoal extends Goal {

    /** 下がり始める近さ(マス)。 */
    private static final double TOO_CLOSE = 5.0;
    /**
     * 経路を頼み直す間隔(tick)。前は寄る・下がる間じゅう毎 tick 頼んでいて、相手が動くたび・届かない相手だと毎 tick 経路を探し直していた
     * (2026-10-07 の記録で兵士の時間の 4 分の 1。2026-10-10、0.1.9 の前に直した。寄り始めが最大この分だけ遅れるだけ)。
     */
    private static final int REPATH_TICKS = 10;

    private final SoldierEntity soldier;
    private int cooldown;
    private int shotsLeft;
    private int seeTime;
    /** 最後に経路を頼んでからの tick。 */
    private int sinceRepath = REPATH_TICKS;
    /** 最後に寄る経路を頼んだときの相手のマス(動いたかを見る)。 */
    private long pathedTargetPos = Long.MIN_VALUE;

    public SoldierShootGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = soldier.getTarget();
        return t != null && t.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void stop() {
        soldier.setAiming(false);
        soldier.getNavigation().stop();
        seeTime = 0;
        shotsLeft = 0;
        sinceRepath = REPATH_TICKS;
        pathedTargetPos = Long.MIN_VALUE;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity target = soldier.getTarget();
        if (target == null) {
            return;
        }
        SoldierKind kind = soldier.kind();
        double d = soldier.distanceTo(target);
        boolean see = soldier.getSensing().hasLineOfSight(target);
        seeTime = see ? seeTime + 1 : 0;
        soldier.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (cooldown > 0) {
            cooldown--;
        }
        sinceRepath++;
        if (see && d <= kind.range) {
            soldier.setAiming(true);
            if (d < TOO_CLOSE) {
                Vec3 away = soldier.position().subtract(target.position()).normalize().scale(4.0);
                if (sinceRepath >= REPATH_TICKS) { // 下がる先は毎 tick 変わるので、頼み直すのは REPATH_TICKS ごと
                    sinceRepath = 0;
                    pathedTargetPos = Long.MIN_VALUE;
                    soldier.getNavigation().moveTo(soldier.getX() + away.x, soldier.getY(), soldier.getZ() + away.z, 1.1);
                }
            } else {
                soldier.getNavigation().stop();
            }
            if (seeTime < 5 || cooldown > 0) { // 構えてから撃つ
                return;
            }
            if (soldier.tryMortar(target)) {
                cooldown = kind.cooldown;
                return;
            }
            if (shotsLeft <= 0) {
                shotsLeft = kind.burst;
            }
            if (SoldierGun.fire(soldier, target, kind)) {
                shotsLeft--;
                cooldown = shotsLeft > 0 ? kind.shotInterval : kind.cooldown;
            } else {
                cooldown = 10; // 味方が射線に入っている: 少し待つ
                soldier.getNavigation().moveTo(target, 0.8); // 回り込む
            }
        } else {
            soldier.setAiming(see);
            // 歩き終えた(届かない相手の途中まで来た)か相手が別のマスへ動いたら頼み直す。どちらも前回から REPATH_TICKS 空ける
            long targetPos = target.blockPosition().asLong();
            if (sinceRepath >= REPATH_TICKS && (soldier.getNavigation().isDone() || targetPos != pathedTargetPos)) {
                sinceRepath = 0;
                pathedTargetPos = targetPos;
                soldier.getNavigation().moveTo(target, 1.0);
            }
        }
    }
}
