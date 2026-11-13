package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import com.yayuy.overgrownvegetation.entity.g1.G1Developed;
import com.yayuy.overgrownvegetation.registry.ModG1;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.Goal;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * 豚の 1 段目の (b) の前半「小さな G1 を背に乗せる」(2026-11-12。仕様 Vault「G1 豚(1 段目)」3 節と残りの問い 9【決定 11-09「チビゾンビは両方」】)。
 * 放り出すのは {@link G1TossGoal}。
 *
 * <ul>
 *   <li>乗せる相手: チビゾンビの段階 0(寄生されたチビゾンビ)と 1 段目(発達した寄生されたチビゾンビ)だけ。鶏などほかの小さい G1 は乗せない。</li>
 *   <li>使うとき: 敵がいない・背が空いている。{@value #SEARCH} ブロック先から探し(1 秒ごと)、そばまで歩く。</li>
 *   <li>乗せる: そばで伏せる(crouch_load 30 tick。背の根が持ち上がる)。{@value #MOUNT_AT} tick 目に乗り手を背に縛る(乗り手は豚に乗っている間殴らない・歩かない)。</li>
 *   <li>乗せている間は豚が少し速い(×1.2。{@link DevelopedParasitized} の乗り手の出し入れで付け外し)。豚が死ぬと乗り手が落ちて 1 秒転ぶ。</li>
 * </ul>
 * 数値はすべて【仮】(このクラスの定数)。
 */
public class G1CarryGoal extends Goal {

    static final double SEARCH = 12.0D;
    static final double LOAD_DISTANCE = 1.4D;
    static final int LOAD_TICKS = 30;
    static final int MOUNT_AT = 18;
    /** 歩いて行っても届かないときに諦める(tick)。 */
    static final int GIVE_UP = 200;

    private final DevelopedParasitized mob;
    @Nullable
    private AbstractParasitizedMonster small;
    private int walking;
    private int load;
    private long nextSearch;

    public G1CarryGoal(DevelopedParasitized mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** 背に乗せられる小さな G1 か。 */
    public static boolean carriable(AbstractParasitizedMonster m) {
        EntityType<?> type = m.getType();
        return type == ModG1.PARASITIZED_BABY_ZOMBIE.get() || type == ModG1.developed(G1Developed.BABY_ZOMBIE).get();
    }

    @Override
    public boolean canUse() {
        if (mob.getTarget() != null || mob.isVehicle() || mob.action() != AbstractParasitizedMonster.ACTION_NONE || mob.isStunned()) return false;
        long now = mob.level().getGameTime();
        if (now < nextSearch) return false;
        nextSearch = now + 20;
        small = find();
        return small != null;
    }

    @Nullable
    private AbstractParasitizedMonster find() {
        AbstractParasitizedMonster best = null;
        double bestD = SEARCH * SEARCH;
        for (AbstractParasitizedMonster m : mob.level().getEntitiesOfClass(AbstractParasitizedMonster.class, mob.getBoundingBox().inflate(SEARCH))) {
            if (m == mob || !m.isAlive() || m.isPassenger() || m.isVehicle() || !carriable(m) || m.isRising()) continue;
            if (m instanceof DevelopedParasitized d && d.latch() != null) continue;
            double dd = mob.distanceToSqr(m);
            if (dd < bestD) {
                bestD = dd;
                best = m;
            }
        }
        return best;
    }

    @Override
    public boolean canContinueToUse() {
        if (load > 0) return load <= LOAD_TICKS && mob.isAlive();
        return small != null && small.isAlive() && !small.isPassenger() && mob.getTarget() == null && !mob.isVehicle()
                && walking < GIVE_UP && mob.distanceToSqr(small) <= SEARCH * SEARCH * 1.5D;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        walking = 0;
        load = 0;
    }

    @Override
    public void stop() {
        if (load > 0) {
            mob.setAction(AbstractParasitizedMonster.ACTION_NONE);
        }
        load = 0;
        small = null;
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (small == null) return;
        mob.getLookControl().setLookAt(small, 30.0F, 30.0F);
        if (load > 0) {
            load++;
            mob.getNavigation().stop();
            small.getNavigation().stop();
            if (load == MOUNT_AT && small.isAlive() && !small.isPassenger() && mob.distanceTo(small) <= LOAD_DISTANCE + 1.2D) {
                small.startRiding(mob, true);
                mob.level().playSound(null, mob.getX(), mob.getY() + 1.0D, mob.getZ(), SoundEvents.CAVE_VINES_PLACE, SoundSource.HOSTILE, 1.0F, 0.7F);
            }
            if (load > LOAD_TICKS) {
                mob.setAction(AbstractParasitizedMonster.ACTION_NONE);
            }
            return;
        }
        walking++;
        if (mob.distanceTo(small) <= LOAD_DISTANCE) {
            load = 1;
            mob.getNavigation().stop();
            small.getNavigation().stop();
            small.stun(LOAD_TICKS); // 乗せる間は相手を待たせる
            mob.setAction(G1Move.CROUCH_LOAD.id());
            return;
        }
        if (walking % 10 == 1) {
            mob.getNavigation().moveTo(small, 1.0D);
        }
    }
}
