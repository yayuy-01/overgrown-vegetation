package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.AbstractVillager;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * 村人の 1 段目の (b) の昼「胞子を配る」(2026-11-13 ユーザー決定「1 と 2 の組み合わせ」。仕様 Vault「G1 村人(1 段目)」3 節 (b)、数は【仮】)。
 * 背の嚢から胞子の塊をちぎって、近くの生き物へ投げ渡す({@link G1SporeLump})。元の村人の「物を投げ渡す」(農民がパンを配る)を植物が使う。
 *
 * <ul>
 *   <li>使うとき: 昼・行動が空き(3 段の 1 撃や種の技の途中でない)。{@value #INTERVAL} tick(10 秒)に 1 回。標的を追っている間も投げる
 *   (G1 は 35 ブロック以内の生き物を必ず標的にするので、「標的がいないとき」に限ると生き物のいる所では配れない。Goal の旗は持たず、追跡と並んで動く)。</li>
 *   <li>相手: {@value #RANGE} ブロック以内で見えている、傷ついた G1(回復 +{@value #HEAL})を先に、いなければ生きた動物・村人(暴露量 +{@value #DOSE})。
 *   プレイヤーは狙わない【仮・問い】(仕様の文は「生きた動物・村人」。飛んでいる塊に当たれば同じ量を浴びる)。</li>
 *   <li>相手を決めた瞬間にアニメ gift(1.1 秒、左腕と脇の嚢だけの重ね。歩きながら投げられる)を流し、手を離す {@value #AIM} tick 目に投げる。</li>
 * </ul>
 */
public class G1SporeGiftGoal extends Goal {

    static final double RANGE = 8.0D;
    static final int INTERVAL = 200;
    static final double DOSE = 300.0D;
    static final float HEAL = 4.0F;
    static final int AIM = 10;
    /** 立ち止まって投げるとき、体を相手へ向ける速さ(°/tick)。【仮】 */
    static final float TURN_PER_TICK = 25.0F;
    static final float THROW_SPEED = 0.8F;
    static final double DROP_PER_DISTANCE_SQ = 0.0234D;

    private final DevelopedParasitized mob;
    @Nullable
    private LivingEntity to;
    private int t;

    public G1SporeGiftGoal(DevelopedParasitized mob) {
        this.mob = mob;
        setFlags(EnumSet.noneOf(Flag.class)); // 追跡(MOVE・LOOK)と並んで動く
    }

    @Override
    public boolean canUse() {
        if (mob.action() != AbstractParasitizedMonster.ACTION_NONE || mob.isStunned()) return false;
        if (!mob.isDayFor() || mob.level().getGameTime() < mob.giftReadyAt()) return false;
        to = pick();
        return to != null;
    }

    @Nullable
    private LivingEntity pick() {
        LivingEntity hurtAlly = null, living = null;
        double dA = Double.MAX_VALUE, dL = Double.MAX_VALUE;
        for (LivingEntity e : mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(RANGE))) {
            if (e == mob || !e.isAlive() || mob.distanceTo(e) > RANGE || !mob.getSensing().hasLineOfSight(e)) continue;
            double d = mob.distanceToSqr(e);
            if (e instanceof AbstractParasitizedMonster m) {
                if (m.getHealth() < m.getMaxHealth() && d < dA) {
                    dA = d;
                    hurtAlly = m;
                }
            } else if ((e instanceof Animal || e instanceof AbstractVillager) && !SporeExposureTracker.isPlantSide(e) && d < dL) {
                dL = d;
                living = e;
            }
        }
        return hurtAlly != null ? hurtAlly : living;
    }

    @Override
    public boolean canContinueToUse() {
        return to != null && to.isAlive() && t <= AIM && mob.action() == AbstractParasitizedMonster.ACTION_NONE;
    }

    @Override
    public void start() {
        t = 0;
        mob.playGift(); // 嚢をちぎって投げる動き(10 tick 目に手を離す = AIM)
    }

    @Override
    public void stop() {
        mob.setGiftReadyAt(mob.level().getGameTime() + INTERVAL);
        to = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (to == null) return;
        if (mob.getTarget() == null) {
            mob.getLookControl().setLookAt(to, 30.0F, 30.0F);
            if (mob.getNavigation().isDone()) {
                // 立ち止まっているときは体ごと相手へ向ける(頭だけ回すと、投げる腕と塊の飛ぶ向きが合わない)。歩いている・追っている間は向きを変えない
                float want = (float) (net.minecraft.util.Mth.atan2(to.getZ() - mob.getZ(), to.getX() - mob.getX()) * net.minecraft.util.Mth.RAD_TO_DEG) - 90.0F;
                float yaw = net.minecraft.util.Mth.approachDegrees(mob.getYRot(), want, TURN_PER_TICK);
                mob.setYRot(yaw);
                mob.setYBodyRot(yaw);
            }
        }
        if (++t == AIM) {
            throwAt(to);
        }
    }

    private void throwAt(LivingEntity target) {
        G1SporeLump lump = new G1SporeLump(mob.level(), mob);
        lump.setLoad(DOSE, HEAL);
        lump.setPos(mob.getX(), mob.getY() + mob.getBbHeight() * 0.8D, mob.getZ());
        double dx = target.getX() - mob.getX();
        double dz = target.getZ() - mob.getZ();
        double dy = target.getY(0.5D) - lump.getY();
        double h = Math.sqrt(dx * dx + dz * dz);
        // 速さ 0.8・重力 0.03 の落下ぶん(≒ 0.0234 × 距離²)だけ上を狙う。ばらつきは雪玉の半分
        lump.shoot(dx, dy + DROP_PER_DISTANCE_SQ * h * h, dz, THROW_SPEED, 1.0F);
        mob.level().addFreshEntity(lump);
        mob.level().playSound(null, mob.getX(), mob.getY() + 1.4D, mob.getZ(), SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 0.7F, 0.8F);
    }
}
