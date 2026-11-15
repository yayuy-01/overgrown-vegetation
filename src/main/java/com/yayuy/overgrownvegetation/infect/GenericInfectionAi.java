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
        removeGoals(mob.targetSelector, g -> true);
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
            if (usesRam(mob)) mob.goalSelector.addGoal(1, new RamGoal(pm, false));
            else if (!(mob instanceof EnderDragon)) mob.goalSelector.addGoal(2, new RamGoal(pm, true)); // 元の攻撃が当たらないときの予備
        }
        mob.setTarget(null);
    }

    /** 付けた動きを外し、外した Goal を戻す。 */
    public static void uninstall(Mob mob) {
        removeGoals(mob.goalSelector, g -> g instanceof FreezeGoal || g instanceof RamGoal);
        removeGoals(mob.targetSelector, g -> g instanceof PreyGoal || g instanceof HurtByTargetGoal);
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

    /**
     * Goal を外す。バニラの {@code removeAllGoals} は動いている Goal を止めずに外すので、外した Goal が旗(狙い・移動など)を握ったまま残り、
     * 後から入れた同じ優先度の Goal が動けなくなる(11-15 の調べで分かった)。先に止めてから外す。
     */
    static void removeGoals(net.minecraft.world.entity.ai.goal.GoalSelector selector, java.util.function.Predicate<Goal> filter) {
        for (WrappedGoal w : new ArrayList<>(selector.getAvailableGoals())) {
            if (filter.test(w.getGoal()) && w.isRunning()) w.stop();
        }
        selector.removeAllGoals(filter);
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

    /**
     * 汎用の体当たり: 狙った相手へ歩く(当てるのは {@link #ramTick}。歩けない Mob も触れたら当たる)。
     * {@code fallback} = 元の攻撃を持つ種の予備(元の攻撃が {@code fallbackTicks} 当たらなかったときだけ動く。{@link #rammingNow})。
     */
    public static final class RamGoal extends Goal {
        private final PathfinderMob mob;
        private final boolean fallback;
        private int repath;

        RamGoal(PathfinderMob mob, boolean fallback) {
            this.mob = mob;
            this.fallback = fallback;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity t = targetOf(mob);
            return t != null && t.isAlive() && !GenericInfection.isTransforming(mob) && (!fallback || rammingNow(mob));
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
            // Brain で動く Mob は Brain が毎 tick 歩く先を書き、こちらの追いかけを上書きする(ヤギなど)→ 追う間は歩く先を消す(ATTACK_TARGET は書かない = ★ 7:2)
            var brain = mob.getBrain();
            if (brain.checkMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET,
                    net.minecraft.world.entity.ai.memory.MemoryStatus.REGISTERED)) {
                brain.eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
            }
            if (--repath <= 0) {
                repath = 10;
                mob.getNavigation().moveTo(t, GenericInfection.spec().ramSpeed());
            }
        }
    }

    /**
     * 狙う相手が替わったかを見て、元の攻撃が当たらないまま {@code fallbackTicks} 過ぎたら、その相手の間は体当たりの予備に切り替える
     * (11-15 ユーザーの不具合「敵対はするが攻撃できていない」: 攻撃の属性はあっても攻撃の Goal を持たない種 = 普通のウサギ・
     * 武器を持たないピリジャー・ほかの MOD の Mob など)。毎 tick({@code GenericInfection#onTick} から)。
     */
    static void trackTarget(Mob mob, GenericInfected g) {
        LivingEntity t = targetOf(mob);
        if (t != g.trackedTarget) {
            g.trackedTarget = t;
            g.targetSince = mob.tickCount;
            g.fallbackOn = false;
            return;
        }
        if (t == null || g.fallbackOn || usesRam(mob) || mob instanceof EnderDragon) return;
        int wait = GenericInfection.spec().fallbackTicks();
        if (mob.tickCount - g.targetSince >= wait && mob.tickCount - mob.getLastHurtMobTimestamp() >= wait) g.fallbackOn = true;
    }

    /** 狙いの保険の間隔(tick)【仮】。 */
    public static final int ACQUIRE_INTERVAL = 20;

    /**
     * 狙いの保険(種を知らなくても効く形。11-15 ユーザーの不具合「ほかの MOD の Mob が襲いに行かない」): 変わった Mob が狙いを持たない間、
     * {@link #ACQUIRE_INTERVAL} ごとに、狙う範囲の中でいちばん近い獲物を直に狙わせる(標的の Goal が何かに邪魔されて動かない Mob 用)。
     */
    static void acquireIfIdle(Mob mob, GenericInfected g) {
        if (targetOf(mob) != null || !(mob.level() instanceof net.minecraft.server.level.ServerLevel level)) return;
        double r = range(mob, GenericInfection.spec());
        LivingEntity best = level.getNearestEntity(level.getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(r, 4.0D, r)),
                TargetingConditions.forCombat().range(r).ignoreLineOfSight().selector(e -> isPrey(mob, e)), mob, mob.getX(), mob.getEyeY(), mob.getZ());
        if (best == null) return;
        mob.setTarget(best);
        g.prey = best;
        acquiredCount++;
    }

    /** 数え(確かめ用): 狙いの保険で狙わせた回数。 */
    public static int acquiredCount;

    /** 今、体当たりで当てに行くか(体当たりの種か、予備に切り替わった)。 */
    public static boolean rammingNow(Mob mob) {
        if (usesRam(mob)) return true;
        return mob.hasData(com.yayuy.overgrownvegetation.registry.ModAttachments.GENERIC_INFECTED)
                && mob.getData(com.yayuy.overgrownvegetation.registry.ModAttachments.GENERIC_INFECTED).fallbackOn;
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
