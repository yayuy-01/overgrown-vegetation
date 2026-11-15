package com.yayuy.overgrownvegetation.infect;

import com.yayuy.overgrownvegetation.data.GenericInfectionSpec;
import com.yayuy.overgrownvegetation.effect.ParasitizedAttackInfectionHandler;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import com.yayuy.overgrownvegetation.spore.SporeFleeGoal;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.BreedGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.TemptGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.Shulker;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * 汎用の感染体の動き(2026-11-15 ユーザー決定。{@link GenericInfection} から呼ぶ)。
 *
 * <ul>
 *     <li>★ 元の攻撃・能力をそのまま使う: 攻撃の Goal・Brain は残し、<b>狙う相手だけ</b>差し替える(標的の Goal = targetSelector を外して
 *     {@link PreyGoal} と被弾の仕返しに)。</li>
 *     <li>★ 狙うのはプレイヤーと植物側でない生き物(元は襲わない Mob も)。視線を問わない。</li>
 *     <li>★ 攻撃を持たない種と Brain で動く Mob は、種を問わず一定の体当たり({@link RamGoal} + 触れたら当たる。{@link #ramTick})。</li>
 *     <li>★ 飼い慣らした Mob は飼い主を忘れる(飼い主も襲う)・乗っている人を振り落とす。</li>
 *     <li>逃げる・怯える・誘われる・繁殖の Goal(パニック・避ける・誘い・繁殖・胞子から逃げる)を外す【仮】。</li>
 *     <li>変わる間は {@link FreezeGoal}(移動・視線・跳びの旗を全部取る)+ 毎 tick の止め({@link GenericInfection})。</li>
 * </ul>
 * 外した Goal は覚えておき、戻すとき({@code revert})に戻す(覚えは保存しない。読み直した個体は元の Goal が新しく作られるので入れ直すだけ)。
 */
public final class GenericInfectionAi {

    private GenericInfectionAi() {
    }

    /** 外した Goal(戻す用)。 */
    private record Removed(List<WrappedGoal> targets, List<WrappedGoal> goals) {
    }



    /** 付けたか(区画の読み直しで二重に付けない)。 */
    public static boolean installed(Mob mob) {
        return mob.targetSelector.getAvailableGoals().stream().anyMatch(g -> g.getGoal() instanceof PreyGoal);
    }

    /** 変わった Mob の動きを付ける(変わったとき・読み直してワールドに入ったとき)。 */
    public static void install(Mob mob) {
        if (installed(mob)) return;
        List<WrappedGoal> targets = new ArrayList<>(mob.targetSelector.getAvailableGoals());
        mob.targetSelector.removeAllGoals(g -> true);
        List<WrappedGoal> goals = new ArrayList<>();
        for (WrappedGoal w : mob.goalSelector.getAvailableGoals()) {
            if (isRemovedGoal(w.getGoal())) goals.add(w);
        }
        for (WrappedGoal w : goals) mob.goalSelector.removeGoal(w.getGoal());
        mob.getData(com.yayuy.overgrownvegetation.registry.ModAttachments.GENERIC_INFECTED).removedGoals = new Removed(targets, goals);

        mob.goalSelector.addGoal(0, new FreezeGoal(mob));
        mob.targetSelector.addGoal(1, new PreyGoal(mob));
        if (mob instanceof PathfinderMob pm) {
            mob.targetSelector.addGoal(2, new HurtByTargetGoal(pm));
            if (usesRam(mob)) mob.goalSelector.addGoal(1, new RamGoal(pm));
        }
        mob.setTarget(null);
    }

    /** 付けた動きを外し、外した Goal を戻す。 */
    public static void uninstall(Mob mob) {
        mob.goalSelector.removeAllGoals(g -> g instanceof FreezeGoal || g instanceof RamGoal);
        mob.targetSelector.removeAllGoals(g -> g instanceof PreyGoal || g instanceof HurtByTargetGoal);
        GenericInfected gi = mob.getData(com.yayuy.overgrownvegetation.registry.ModAttachments.GENERIC_INFECTED);
        Removed r = gi.removedGoals instanceof Removed rr ? rr : null;
        gi.removedGoals = null;
        gi.prey = null;
        if (r != null) {
            for (WrappedGoal w : r.targets()) mob.targetSelector.addGoal(w.getPriority(), w.getGoal());
            for (WrappedGoal w : r.goals()) mob.goalSelector.addGoal(w.getPriority(), w.getGoal());
        }
        mob.setTarget(null);
    }

    /** 変わったときの 1 回きり: 飼い主を忘れる・乗っている人を振り落とす(★ 問い 9)。 */
    public static void forgetOwner(Mob mob) {
        if (mob instanceof TamableAnimal tame) {
            tame.setOrderedToSit(false);
            tame.setInSittingPose(false);
            tame.setTame(false, false);
            tame.setOwnerUUID(null);
        } else if (mob instanceof AbstractHorse horse) {
            horse.setTamed(false);
            horse.setOwnerUUID(null);
        }
        mob.ejectPassengers();
        if (mob.isLeashed()) mob.dropLeash(true, true);
    }

    /** 外す Goal か: 逃げる・怯える・誘われる・繁殖(元は襲わない Mob が獲物から逃げないように)。 */
    private static boolean isRemovedGoal(Goal g) {
        return g instanceof PanicGoal || g instanceof AvoidEntityGoal<?> || g instanceof TemptGoal || g instanceof BreedGoal
                || g instanceof SporeFleeGoal;
    }

    /**
     * 汎用の体当たりを使うか: 攻撃を持たない種(攻撃の属性が無く、撃つ Mob でもない)と、Brain で動く Mob(★ 問い 7: Brain の Mob はどれも体当たりだけ)。
     * エンダードラゴンは元の動きのまま(★ 問い 8)。
     */
    public static boolean usesRam(Mob mob) {
        if (mob instanceof EnderDragon) return false;
        if (isBrainMob(mob)) return true;
        return !hasOwnAttack(mob);
    }

    /** 元の攻撃を持つか(攻撃の属性・撃つ Mob・ガスト・シュルカー)。 */
    public static boolean hasOwnAttack(Mob mob) {
        return mob.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE) || mob instanceof RangedAttackMob
                || mob instanceof Ghast || mob instanceof Shulker;
    }

    /** Brain で動く Mob か(記憶の型が登録されている)。 */
    public static boolean isBrainMob(Mob mob) {
        return !mob.getBrain().getMemories().isEmpty();
    }

    /** 狙う範囲(追跡範囲の属性、無ければ設計値)。 */
    static double range(Mob mob, GenericInfectionSpec s) {
        return mob.getAttributes().hasAttribute(Attributes.FOLLOW_RANGE) ? Math.max(1.0D, mob.getAttributeValue(Attributes.FOLLOW_RANGE))
                : s.targetRange();
    }

    /** 狙ってよい相手か: 植物側でない生き物(プレイヤーも、元は襲わない Mob も)。創造・観戦・防具立て・無敵は外す。 */
    public static boolean isPrey(Mob self, LivingEntity e) {
        if (e == self || e instanceof ArmorStand || !e.isAlive() || !e.canBeSeenAsEnemy()) return false;
        if (!EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(e)) return false;
        if (self.isPassengerOfSameVehicle(e)) return false;
        return !SporeExposureTracker.isPlantSide(e) && !ParasitizedAttackInfectionHandler.isOvergrownMob(e);
    }

    // ---------------------------------------------------------------- Goal

    /** 植物側でない生き物を狙う(視線を問わない。★ 問い 13)。 */
    public static final class PreyGoal extends NearestAttackableTargetGoal<LivingEntity> {
        private final double range;

        PreyGoal(Mob mob) {
            super(mob, LivingEntity.class, 10, false, false, null);
            this.range = range(mob, GenericInfection.spec());
            this.targetConditions = TargetingConditions.forCombat().range(range).ignoreLineOfSight().selector(e -> isPrey(mob, e));
        }

        @Override
        public boolean canUse() {
            return !GenericInfection.isTransforming(mob) && super.canUse();
        }

        @Override
        public void start() {
            super.start();
            if (target != null) mob.getData(com.yayuy.overgrownvegetation.registry.ModAttachments.GENERIC_INFECTED).prey = target;
        }

        @Override
        protected double getFollowDistance() {
            return range;
        }
    }

    /**
     * 体当たり・呼び寄せが見る狙う相手: {@code getTarget()}、無ければ自前の相手({@link GenericInfected#prey}。Brain で getTarget を上書きする種用)。
     * 自前の相手は、死んだ・植物側になった・狙う範囲の 1.5 倍より遠くなったら忘れる。
     */
    public static LivingEntity targetOf(Mob mob) {
        LivingEntity t = mob.getTarget();
        if (t != null) return t;
        if (!mob.hasData(com.yayuy.overgrownvegetation.registry.ModAttachments.GENERIC_INFECTED)) return null;
        GenericInfected g = mob.getData(com.yayuy.overgrownvegetation.registry.ModAttachments.GENERIC_INFECTED);
        LivingEntity p = g.prey;
        if (p == null) return null;
        double r = range(mob, GenericInfection.spec()) * 1.5D;
        if (p.isRemoved() || !isPrey(mob, p) || p.level() != mob.level() || mob.distanceToSqr(p) > r * r) {
            g.prey = null;
            return null;
        }
        return p;
    }

    /** 変わる間: 移動・視線・跳びの旗を全部取り、ほかの Goal を止める。 */
    public static final class FreezeGoal extends Goal {
        private final Mob mob;

        FreezeGoal(Mob mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
        }

        @Override
        public boolean canUse() {
            return GenericInfection.isTransforming(mob);
        }

        @Override
        public void start() {
            mob.getNavigation().stop();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            mob.getNavigation().stop();
        }
    }

    /** 汎用の体当たり: 狙った相手へ歩く(当てるのは {@link #ramTick}。歩けない Mob も触れたら当たる)。 */
    public static final class RamGoal extends Goal {
        private final PathfinderMob mob;
        private int repath;

        RamGoal(PathfinderMob mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity t = targetOf(mob);
            return t != null && t.isAlive() && !GenericInfection.isTransforming(mob);
        }

        @Override
        public void start() {
            repath = 0;
        }

        @Override
        public void stop() {
            mob.getNavigation().stop();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            LivingEntity t = targetOf(mob);
            if (t == null) return;
            mob.getLookControl().setLookAt(t, 30.0F, 30.0F);
            if (--repath <= 0) {
                repath = 10;
                mob.getNavigation().moveTo(t, GenericInfection.spec().ramSpeed());
            }
        }
    }

    /**
     * 体当たりの当たり(毎 tick、変わり終えた体当たりの Mob だけ): 狙った相手と当たり判定が触れていて冷却が明けていれば、一定のダメージ。
     * 歩けない Mob(コウモリなど PathfinderMob でないもの)は、相手の方へ少し寄せる。
     */
    static void ramTick(Mob mob, GenericInfected g, long now) {
        LivingEntity t = targetOf(mob);
        if (t == null || !t.isAlive()) return;
        GenericInfectionSpec s = GenericInfection.spec();
        if (!(mob instanceof PathfinderMob) && mob.distanceToSqr(t) < range(mob, s) * range(mob, s)) {
            net.minecraft.world.phys.Vec3 to = t.position().add(0.0D, t.getBbHeight() * 0.5D, 0.0D).subtract(mob.position()).normalize();
            mob.setDeltaMovement(mob.getDeltaMovement().scale(0.9D).add(to.scale(0.06D)));
        }
        if (now - g.lastRamTick < s.ramCooldownTicks()) return;
        if (!mob.getBoundingBox().inflate(0.25D).intersects(t.getBoundingBox())) return;
        if (t.hurt(mob.damageSources().mobAttack(mob), (float) s.ramDamage())) {
            mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            mob.setLastHurtMob(t);
        }
        g.lastRamTick = now;
    }

    /** 飼い主の印が残っていたら消す(読み直した個体など)。 */
    static void keepOwnerless(Mob mob) {
        if (mob instanceof OwnableEntity o && o.getOwnerUUID() != null) forgetOwner(mob);
        else if (mob.isVehicle() && mob.getPassengers().stream().anyMatch(p -> p instanceof net.minecraft.world.entity.player.Player)) {
            mob.ejectPassengers();
        }
    }
}
