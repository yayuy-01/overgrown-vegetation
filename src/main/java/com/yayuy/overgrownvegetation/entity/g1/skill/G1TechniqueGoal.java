package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * G1 の 1 段目の種の技の土台(2026-11-12、能力の第 1 回)。段({@link G1Move})を決まった長さで順に流し、決まった時刻に中身を起こす。
 * 段階 0 の 3 段の 1 撃({@code G1StrikeGoal})と同じ作り: 前触れの {@link #aimUntil} tick までは標的の方を向き、そこから向きを止める
 * (「横へ出る」でよけられる)。振り出した技は最後まで(途中で止めると絵が飛ぶ)。被弾しても止まらない(種の技で止めたいものは {@link #interrupted})。
 *
 * <ul>
 *   <li>始める条件: 行動の段が空き({@code ACTION_NONE}、または {@link #canStartFrom} が許す段)・技と技の間が明けている
 *   ({@link DevelopedParasitized#techniqueReady})・この技の冷却が明けている・{@link #ready}。</li>
 *   <li>終わり: 冷却 {@link #cooldown}・技と技の間 10〜20 tick【仮・共通】を数え始め、行動の段を戻す({@link DevelopedParasitized#restingAction})。</li>
 * </ul>
 * 優先度は 3 段の 1 撃(2)より高い 1 で登録する(同じ MOVE・LOOK を持つので、技の間は 1 撃が動かない。1 撃の途中は行動の段が空かないので割り込まない)。
 */
public abstract class G1TechniqueGoal extends Goal {

    protected final DevelopedParasitized mob;
    private final G1Move[] stages;
    private final int[] lengths;
    /** 技の中の tick(1 から)。0 = 動いていない。 */
    protected int t;
    private int stage;
    private int stageStart;
    private long readyAt;
    protected float lockedYaw;
    @Nullable
    protected LivingEntity target;

    protected G1TechniqueGoal(DevelopedParasitized mob, G1Move[] stages, int[] lengths) {
        this.mob = mob;
        this.stages = stages;
        this.lengths = lengths;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** この技の中身の条件(標的の距離など)。行動の段・冷却・技の間は土台が見る。 */
    protected abstract boolean ready();

    /** 各 tick の中身。{@code stageIndex} 段目の {@code tickInStage} tick 目(1 から)。 */
    protected abstract void onTick(int stageIndex, int tickInStage);

    /** 技が終わってから次に使えるまで(tick)。 */
    protected abstract int cooldown();

    /** 標的の方を向き続ける最後の tick(技の中の通し)。既定は最初の段の 3/4。 */
    protected int aimUntil() {
        return Math.max(1, lengths[0] * 3 / 4);
    }

    /** 始めてよい行動の段か(既定は空きだけ。チビゾンビの袋は取り付き中も)。 */
    protected boolean canStartFrom(int action) {
        return action == AbstractParasitizedMonster.ACTION_NONE;
    }

    /** 途中で止める理由があるか(既定は無し)。 */
    protected boolean interrupted() {
        return false;
    }

    /** 標的へ向きを合わせて止めるか(既定はする。取り付いたまま噴くチビゾンビは、向きを取り付きに任せる)。 */
    protected boolean lockFacing() {
        return true;
    }

    /** 向きを追うときの 1 tick の回り(度)。既定 30。根を張って据えたスケルトンは遅い(第 3 回)。 */
    protected float turnRate() {
        return 30.0F;
    }

    /** 動いている間は navigation を止めるか(既定は止める)。 */
    protected boolean holdStill() {
        return true;
    }

    @Override
    public boolean canUse() {
        if (!mob.isAlive() || mob.isEvolving() || mob.isStunned() || mob.isPassenger()) return false;
        if (!canStartFrom(mob.action()) || !mob.techniqueReady() || mob.level().getGameTime() < readyAt) return false;
        target = mob.getTarget();
        return ready();
    }

    @Override
    public boolean canContinueToUse() {
        return t > 0 && mob.isAlive() && !interrupted();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        t = 1;
        stage = 0;
        stageStart = 1;
        lockedYaw = mob.getYRot();
        if (holdStill()) mob.getNavigation().stop();
        mob.setAction(stages[0].id());
    }

    @Override
    public void stop() {
        if (t > 0) {
            // 途中で止められた(死・interrupted)
            mob.setAction(mob.restingAction());
        }
        t = 0;
        readyAt = mob.level().getGameTime() + cooldown();
        mob.markTechniqueUsed();
    }

    @Override
    public void tick() {
        if (t <= 0) return;
        if (holdStill()) mob.getNavigation().stop();
        if (lockFacing()) {
            if (t <= aimUntil() && target != null && target.isAlive()) {
                mob.getLookControl().setLookAt(target, 60.0F, 30.0F);
                double dx = target.getX() - mob.getX();
                double dz = target.getZ() - mob.getZ();
                float want = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
                lockedYaw = Mth.approachDegrees(mob.getYRot(), want, turnRate());
            }
            mob.setYRot(lockedYaw);
            mob.setYBodyRot(lockedYaw);
            mob.yHeadRot = lockedYaw;
        }

        onTick(stage, t - stageStart + 1);
        if (t - stageStart + 1 >= lengths[stage]) {
            stage++;
            if (stage >= stages.length) {
                t = 0;
                mob.setAction(mob.restingAction());
                return; // canContinueToUse が false になって stop()
            }
            stageStart = t + 1;
            mob.setAction(stages[stage].id());
        }
        t++;
    }

    /** 冷却を今から数え直す(GameTest・確かめ用のコマンドが冷却を飛ばすとき)。 */
    public void resetCooldown() {
        readyAt = 0L;
    }

    /**
     * 冷却の残り(tick)。保存用: 世界の時計ではなく残りで持つ(鶏の {@code LayIn} と同じ)。技の途中なら、今終わったものとして冷却まるごと
     * (途中で保存された技は読み込み直しで続きを流さないので、放った後の段で保存されても 2 回目をすぐ出さない)。
     */
    public long cooldownLeft() {
        if (t > 0) return cooldown();
        return Math.max(0L, readyAt - mob.level().getGameTime());
    }

    /** 保存した冷却の残りを戻す(読み込み)。冷却の長さで頭打ち。 */
    public void setCooldownLeft(long ticks) {
        readyAt = mob.level().getGameTime() + Math.max(0L, Math.min(ticks, (long) cooldown()));
    }
}
