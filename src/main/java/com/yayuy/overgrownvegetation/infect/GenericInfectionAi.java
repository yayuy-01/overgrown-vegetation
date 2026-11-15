package com.yayuy.overgrownvegetation.infect;

import com.yayuy.overgrownvegetation.data.GenericInfectionSpec;
import com.yayuy.overgrownvegetation.effect.ParasitizedAttackInfectionHandler;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import com.yayuy.overgrownvegetation.spore.SporeFleeGoal;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.BreedGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomSwimmingGoal;
import net.minecraft.world.entity.ai.goal.TemptGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.animal.WaterAnimal;
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
 *     <li>★ 元の攻撃・能力をそのまま使う(11-15「攻撃の方法を持つ mob はそのままそれを使いまわす」): 攻撃の Goal・Brain は残し、<b>狙う相手だけ</b>
 *     差し替える(標的の Goal = targetSelector を外して {@link PreyGoal} と被弾の仕返しに。Brain の Mob は ATTACK_TARGET に書く・ヤギは突進の冷却を消す
 *     = ★ 問い 7 を 1 に戻した)。</li>
 *     <li>★ 狙うのはプレイヤーと植物側でない生き物(元は襲わない Mob も)。視線を問わない。</li>
 *     <li>★ 攻撃の手段を持たない種({@link #hasOwnAttack} が偽)は、ゾンビのような近接({@link MeleeGoal}: 歩いて行き、届けば殴る・腕を振る)。
 *     しぐさ(草を食べる・見回す・寝る・ねだる など)の Goal と Brain の振る舞いを外し、うろつく・泳ぐ・追う・殴るだけにする。</li>
 *     <li>元の攻撃を持つ種でも、狙った相手を {@code fallbackTicks} 傷付けられなければ、その相手の間は同じ近接を予備に使う(最後の手段)。</li>
 *     <li>★ 飼い慣らした Mob は飼い主を忘れる(飼い主も襲う)・乗っている人を振り落とす。</li>
 *     <li>逃げる・怯える・誘われる・繁殖の Goal を外す【仮】。変わる間は {@link FreezeGoal} + 毎 tick の止め({@link GenericInfection})。</li>
 * </ul>
 * 外した Goal は覚えておき、戻すとき({@code revert})に戻す(覚えは保存しない。読み直した個体は元の Goal が新しく作られるので入れ直すだけ)。
 * Brain の振る舞いは外すと戻せない(戻した後もワールドを読み直すまで振る舞いが無い。確かめ用の revert だけの話)。
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
        GenericInfected gi = mob.getData(ModAttachments.GENERIC_INFECTED);
        boolean attackless = !hasOwnAttack(mob); // しぐさを外す前に見る(外すと攻撃の Goal の名前も消える)
        gi.attackless = attackless;
        List<WrappedGoal> targets = new ArrayList<>(mob.targetSelector.getAvailableGoals());
        removeGoals(mob.targetSelector, g -> true);
        List<WrappedGoal> goals = new ArrayList<>();
        for (WrappedGoal w : mob.goalSelector.getAvailableGoals()) {
            if (isRemovedGoal(w.getGoal()) || (attackless && !isMovementGoal(w.getGoal()))) goals.add(w);
        }
        for (WrappedGoal w : goals) mob.goalSelector.removeGoal(w.getGoal());
        gi.removedGoals = new Removed(targets, goals);
        if (attackless && isBrainMob(mob)) stripBrain(mob);

        mob.goalSelector.addGoal(0, new FreezeGoal(mob));
        mob.targetSelector.addGoal(1, new PreyGoal(mob));
        if (mob instanceof PathfinderMob pm) {
            mob.targetSelector.addGoal(2, new HurtByTargetGoal(pm));
            if (attackless) {
                mob.goalSelector.addGoal(1, new MeleeGoal(pm, false));
                addWanderAndSwim(pm);
            } else if (!(mob instanceof EnderDragon)) {
                mob.goalSelector.addGoal(2, new MeleeGoal(pm, true)); // 元の攻撃が相手を傷付けられないときの予備(最後の手段)
            }
        }
        mob.setTarget(null);
    }

    /** 付けた動きを外し、外した Goal を戻す。 */
    public static void uninstall(Mob mob) {
        removeGoals(mob.goalSelector, g -> g instanceof FreezeGoal || g instanceof MeleeGoal || g instanceof AddedGoal);
        removeGoals(mob.targetSelector, g -> g instanceof PreyGoal || g instanceof HurtByTargetGoal);
        GenericInfected gi = mob.getData(ModAttachments.GENERIC_INFECTED);
        Removed r = gi.removedGoals instanceof Removed rr ? rr : null;
        gi.removedGoals = null;
        gi.prey = null;
        gi.attackless = null;
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
    static void removeGoals(GoalSelector selector, java.util.function.Predicate<Goal> filter) {
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

    /** 攻撃の手段を持たない種でも残す Goal: 浮く・泳ぐ・うろつく・動き(名前で見る。ほかの MOD の Goal も同じ決まり)。 */
    private static boolean isMovementGoal(Goal g) {
        return goalNameHas(g, "Float", "Swim", "Stroll", "Wander", "Movement");
    }

    /** 攻撃の手段を持たない種に、浮く・うろつく(陸)・泳ぐ(水)を足す(残っていなければ)。 */
    private static void addWanderAndSwim(PathfinderMob pm) {
        boolean hasFloat = false, hasWander = false;
        for (WrappedGoal w : pm.goalSelector.getAvailableGoals()) {
            if (w.getGoal() instanceof FloatGoal || goalNameHas(w.getGoal(), "Float")) hasFloat = true;
            if (goalNameHas(w.getGoal(), "Stroll", "Wander", "Swimming", "Movement")) hasWander = true;
        }
        if (!hasFloat && !(pm instanceof WaterAnimal)) pm.goalSelector.addGoal(0, new AddedGoal(new FloatGoal(pm)));
        if (!hasWander) {
            pm.goalSelector.addGoal(7, new AddedGoal(pm instanceof WaterAnimal ? new RandomSwimmingGoal(pm, 1.0D, 10)
                    : new WaterAvoidingRandomStrollGoal(pm, 1.0D)));
        }
    }

    /** 攻撃の手段を持たない Brain の種(ラクダ・スニッファー・アルマジロ・アレイなど)の振る舞いを止めて外す(しぐさごと)。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void stripBrain(Mob mob) {
        Brain brain = mob.getBrain();
        if (mob.level() instanceof ServerLevel level) brain.stopAll(level, mob);
        brain.removeAllBehaviors();
    }

    /**
     * 元の攻撃を持つか(★ 11-15: 持つ種はしぐさも攻撃も元のまま)。
     * <ul>
     *     <li>撃つ Mob・ガスト・シュルカー・エンダードラゴン → 持つ。</li>
     *     <li>Brain で動く Mob → 攻撃の属性があり、Brain が ATTACK_TARGET か RAM_TARGET(ヤギの突進)を持つなら持つ。</li>
     *     <li>ほかの MOD の Mob → 攻撃の属性があるか、攻撃らしい名前の Goal があれば持つ(Goal の名前は作りがまちまちなので、属性を信じる。
     *     出なければ予備の近接になる)。</li>
     *     <li>バニラの Mob → 攻撃らしい名前の Goal(Attack・Shoot・Spell・Charge・Melee・Ranged・Leap・Pounce)を持つなら持つ
     *     (普通のウサギは属性だけ持つので持たない)。</li>
     * </ul>
     */
    public static boolean hasOwnAttack(Mob mob) {
        if (mob.hasData(ModAttachments.GENERIC_INFECTED)) {
            Boolean a = mob.getData(ModAttachments.GENERIC_INFECTED).attackless;
            if (a != null) return !a;
        }
        if (mob instanceof RangedAttackMob || mob instanceof Ghast || mob instanceof Shulker || mob instanceof EnderDragon) return true;
        boolean attr = mob.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE);
        if (isBrainMob(mob)) {
            Brain<?> b = mob.getBrain();
            return attr && (b.checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED)
                    || b.checkMemory(MemoryModuleType.RAM_TARGET, MemoryStatus.REGISTERED));
        }
        boolean attackGoal = mob.goalSelector.getAvailableGoals().stream().anyMatch(w -> isAttackGoal(w.getGoal()));
        boolean vanilla = "minecraft".equals(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getNamespace());
        return vanilla ? attackGoal : (attr || attackGoal);
    }

    private static boolean isAttackGoal(Goal g) {
        return goalNameHas(g, "Attack", "Shoot", "Spell", "Charge", "Melee", "Ranged", "Leap", "Pounce");
    }

    /** Goal の型(親の型も)の名前に、どれかの言葉が入っているか。 */
    private static boolean goalNameHas(Goal g, String... words) {
        for (Class<?> c = g.getClass(); c != null && c != Goal.class && c != Object.class; c = c.getSuperclass()) {
            String n = c.getSimpleName();
            for (String w : words) {
                if (n.contains(w)) return true;
            }
        }
        return false;
    }

    /** 汎用の近接だけで戦う種か(攻撃の手段を持たない種)。エンダードラゴンは元の動きのまま(★ 問い 8)。 */
    public static boolean usesRam(Mob mob) {
        return !(mob instanceof EnderDragon) && !hasOwnAttack(mob);
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
            if (target != null) mob.getData(ModAttachments.GENERIC_INFECTED).prey = target;
        }

        @Override
        protected double getFollowDistance() {
            return range;
        }
    }

    /**
     * 近接・呼び寄せ・Brain への書き込みが見る狙う相手: {@code getTarget()}、無ければ自前の相手({@link GenericInfected#prey}。
     * Brain で getTarget を上書きする種用)。自前の相手は、死んだ・植物側になった・狙う範囲の 1.5 倍より遠くなったら忘れる。
     */
    public static LivingEntity targetOf(Mob mob) {
        LivingEntity t = mob.getTarget();
        if (t != null) return t;
        return validPrey(mob);
    }

    private static LivingEntity validPrey(Mob mob) {
        if (!mob.hasData(ModAttachments.GENERIC_INFECTED)) return null;
        GenericInfected g = mob.getData(ModAttachments.GENERIC_INFECTED);
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

    /** 攻撃の手段を持たない種に足した Goal(浮く・うろつく・泳ぐ)の包み(戻すときに見分ける)。 */
    static final class AddedGoal extends Goal {
        private final Goal inner;

        AddedGoal(Goal inner) {
            this.inner = inner;
            setFlags(inner.getFlags());
        }

        @Override
        public boolean canUse() {
            return inner.canUse();
        }

        @Override
        public boolean canContinueToUse() {
            return inner.canContinueToUse();
        }

        @Override
        public void start() {
            inner.start();
        }

        @Override
        public void stop() {
            inner.stop();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return inner.requiresUpdateEveryTick();
        }

        @Override
        public void tick() {
            inner.tick();
        }
    }

    /**
     * 汎用の近接(ゾンビのように: 狙った相手へ歩き、届けば殴る。殴るのは {@link #ramTick})。
     * {@code fallback} = 元の攻撃を持つ種の予備(元の攻撃が相手を {@code fallbackTicks} 傷付けられなかったときだけ動く。{@link #rammingNow})。
     */
    public static final class MeleeGoal extends Goal {
        private final PathfinderMob mob;
        private final boolean fallback;
        private int repath;

        MeleeGoal(PathfinderMob mob, boolean fallback) {
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
            mob.setAggressive(true);
        }

        @Override
        public void stop() {
            mob.getNavigation().stop();
            mob.setAggressive(false);
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
            // Brain で動く Mob は Brain が毎 tick 歩く先を書き、こちらの追いかけを上書きする(ヤギなど)→ 追う間は歩く先を消す
            Brain<?> brain = mob.getBrain();
            if (brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)) brain.eraseMemory(MemoryModuleType.WALK_TARGET);
            if (--repath <= 0) {
                repath = 10;
                mob.getNavigation().moveTo(t, GenericInfection.spec().ramSpeed());
            }
        }
    }

    /**
     * 狙う相手が替わったかを見て、元の攻撃が相手を傷付けられないまま {@code fallbackTicks} 過ぎたら、その相手の間は近接の予備に切り替える。
     * 「傷付けた」は相手の側の記録(最後に傷付けた Mob とその時刻)でも見る —— MOD の技が {@code doHurtTarget} を通さず直に {@code hurt} を呼ぶと、
     * こちらの「最後に当てた時刻」は進まないため(11-15 ユーザー「攻撃の方法を持つ mob はそのままそれを使いまわす」で予備を最後の手段に下げた)。
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
        if (mob.tickCount - g.targetSince < wait) return;
        boolean hitByMe = mob.tickCount - mob.getLastHurtMobTimestamp() < wait
                || (t.getLastHurtByMob() == mob && t.tickCount - t.getLastHurtByMobTimestamp() < wait);
        if (!hitByMe) g.fallbackOn = true;
    }

    /**
     * Brain で動き元の攻撃を持つ Mob に、狙う相手を伝える(★ 問い 7 を 1 に戻した 11-15): ATTACK_TARGET に書く(ピグリン・ホグリン・ゾグリン・
     * ウォーデン・ブリーズ・ウーパールーパー・カエルなど)。ヤギは突進の行き先を自分で選ぶので、突進の冷却を消して突進させる。間隔ごと。
     */
    static void feedBrain(Mob mob) {
        if (!isBrainMob(mob) || usesRam(mob)) return;
        LivingEntity p = validPrey(mob);
        if (p == null) return;
        Brain<?> brain = mob.getBrain();
        if (brain.checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED)
                && brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) != p) {
            brain.setMemory(MemoryModuleType.ATTACK_TARGET, p);
        }
        if (brain.checkMemory(MemoryModuleType.RAM_COOLDOWN_TICKS, MemoryStatus.REGISTERED)) brain.eraseMemory(MemoryModuleType.RAM_COOLDOWN_TICKS);
    }

    /** 狙いの保険の間隔(tick)【仮】。 */
    public static final int ACQUIRE_INTERVAL = 20;

    /**
     * 狙いの保険(種を知らなくても効く形): 変わった Mob が狙いを持たない間、{@link #ACQUIRE_INTERVAL} ごとに、狙う範囲の中でいちばん近い獲物を
     * 直に狙わせる(標的の Goal が何かに邪魔されて動かない Mob 用)。
     */
    static void acquireIfIdle(Mob mob, GenericInfected g) {
        if (targetOf(mob) != null || !(mob.level() instanceof ServerLevel level)) return;
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

    /** 今、近接で当てに行くか(近接だけの種か、予備に切り替わった)。 */
    public static boolean rammingNow(Mob mob) {
        if (usesRam(mob)) return true;
        return mob.hasData(ModAttachments.GENERIC_INFECTED) && mob.getData(ModAttachments.GENERIC_INFECTED).fallbackOn;
    }

    /**
     * 近接の当たり(毎 tick、近接で当てに行く Mob だけ): 届く距離(バニラの近接と同じ = 体の幅から決まる攻撃の箱)に入っていて冷却が明けていれば、
     * 腕を振って一定のダメージ。歩けない Mob(コウモリなど PathfinderMob でないもの・イカ)は、相手の方へ少し寄せ、触れたら当たる。
     */
    static void ramTick(Mob mob, GenericInfected g, long now) {
        LivingEntity t = targetOf(mob);
        if (t == null || !t.isAlive()) return;
        GenericInfectionSpec s = GenericInfection.spec();
        if (now - g.lastRamTick < s.ramCooldownTicks()) return;
        boolean reach = mob.isWithinMeleeAttackRange(t) || mob.getBoundingBox().inflate(0.25D).intersects(t.getBoundingBox());
        if (!reach) return;
        mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        if (t.hurt(mob.damageSources().mobAttack(mob), (float) s.ramDamage())) mob.setLastHurtMob(t);
        g.lastRamTick = now;
    }

    /**
     * 毎 tick の後(元の処理の後):
     * <ul>
     *     <li>歩けない Mob(コウモリなど PathfinderMob でないもの・イカ)が近接で当てに行く間は、相手の方へ向けて動かす(元の飛び方・泳ぎ方が
     *     毎 tick 速さを書き換えるので、その後で書く。元の動きは 1 割ほど混ざる)。</li>
     *     <li>攻撃の手段を持たない馬の草食み・後ろ足立ちを止める(Goal ではなく馬の中の処理なので)。</li>
     * </ul>
     */
    static void afterTick(Mob mob) {
        boolean walker = mob instanceof PathfinderMob && !(mob instanceof net.minecraft.world.entity.animal.Squid);
        if (!walker && !GenericInfection.isTransforming(mob) && rammingNow(mob)) {
            LivingEntity t = targetOf(mob);
            double r = range(mob, GenericInfection.spec());
            if (t != null && t.isAlive() && mob.distanceToSqr(t) < r * r) {
                net.minecraft.world.phys.Vec3 to = t.position().add(0.0D, t.getBbHeight() * 0.5D, 0.0D).subtract(mob.position()).normalize();
                mob.setDeltaMovement(mob.getDeltaMovement().scale(0.3D).add(to.scale(0.25D)));
            }
        }
        if (mob instanceof AbstractHorse horse && usesRam(mob)) {
            if (horse.isEating()) horse.setEating(false);
            if (horse.isStanding()) horse.setStanding(false);
        }
    }

    /** 飼い主の印が残っていたら消す(読み直した個体など)。 */
    static void keepOwnerless(Mob mob) {
        if (mob instanceof OwnableEntity o && o.getOwnerUUID() != null) forgetOwner(mob);
        else if (mob.isVehicle() && mob.getPassengers().stream().anyMatch(p -> p instanceof net.minecraft.world.entity.player.Player)) {
            mob.ejectPassengers();
        }
    }
}
