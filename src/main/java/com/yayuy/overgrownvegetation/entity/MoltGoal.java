package com.yayuy.overgrownvegetation.entity;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * リトプレウラの<b>脱皮</b>。立ち止まって殻を脱ぎ、その場に抜け殻ブロックを残す
 * (ユーザー指定 2026-09-21)。
 *
 * <p>やることは「動きを止めて所定の tick 数だけ待つ」だけ。殻を出す・抜ける・置く、は
 * すべて {@link LithopleuraEntity} とアニメーション側が持っている ——
 * <b>ゴールは「いつ始めるか」と「途中で邪魔されたらやめる」だけを決める</b>。
 *
 * <p><b>殴られたら中断する</b>。逃げる方が先なので、{@link Flag#MOVE} を押さえているこのゴールは
 * 逃走ゴール(優先度が上)に奪われる。そのとき {@link #stop()} が殻を引っ込めるので、
 * 出したままになることはない。
 */
public class MoltGoal extends Goal {

    private final LithopleuraEntity critter;

    public MoltGoal(LithopleuraEntity critter) {
        this.critter = critter;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return this.critter.readyToMolt();
    }

    @Override
    public boolean canContinueToUse() {
        // 水に入る・落ちるなど、足場が変わったらやめる(空中で殻だけ残るのを防ぐ)。
        return this.critter.isMolting() && this.critter.onGround() && !this.critter.isInWater();
    }

    @Override
    public void start() {
        this.critter.getNavigation().stop();
        this.critter.setDeltaMovement(Vec3.ZERO);
        this.critter.beginMolt();
    }

    @Override
    public void stop() {
        if (this.critter.isMolting()) {
            this.critter.endMolt();   // 中断。殻は引っ込める
        }
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        this.critter.getNavigation().stop();
        this.critter.setDeltaMovement(0.0D, this.critter.getDeltaMovement().y, 0.0D);
        this.critter.advanceMolt();
    }
}
