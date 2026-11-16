package com.yayuy.overgrownvegetation.infect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Infection;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.GenericInfectionSpec;
import com.yayuy.overgrownvegetation.network.GenericInfectionPayload;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.spore.MobInfection;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 汎用の感染体(2026-11-15 ユーザー決定。専用の G1 が無い生き物が、生きたまま植物側に変わる)の土台。
 * 正本は Vault「G1 媒介種(寄生された動物)」の「汎用の感染体」節、数は {@code spore_field.json} の {@code genericInfection}、
 * 計画と問いの答えは共有ノート {@code generic-infection-plan.md}。
 *
 * <h2>流れ</h2>
 * <ul>
 *     <li><b>変わる所</b>(★): 感染の死({@link G1Infection#isInfectionDeath} = 胞子の 20 ダメージ・G1 に殴り殺された)で、なれる Mob
 *     ({@link #canTurn})なら死の知らせを止め({@link #onDeathTurn}、優先度 HIGH = 飛び火・G1 の死体・萌芽体より前)、変える。
 *     ピースフルでは変わらない(今までどおり死ぬ)。萌芽体が生えるのは、ピースフル・{@code enabled} false・除外の表の種だけ。</li>
 *     <li><b>体力</b>(★): 胞子の傷({@code spore_scar})を外して元の最大体力に戻し、満タンで変わる。ボスは最大体力 × {@code bossMaxHealthFactor}。</li>
 *     <li><b>印</b>: {@link GenericInfected} を保存し、追跡しているクライアントへ送る({@link GenericInfectionPayload})。</li>
 *     <li><b>変わる間</b>({@code transformTicks}【仮】): 動きと攻撃を止める(ナビ・狙い・水平の動き・Brain の歩く先と狙い)。</li>
 *     <li><b>動き</b>: {@link GenericInfectionAi}(狙う相手の差し替え・汎用の体当たり・飼い主を忘れる)。</li>
 *     <li><b>敵と味方</b>: 変わった Mob は植物側({@link SporeExposureTracker#isPlantSide}・{@code PlantSideRules.TRANSFORMED})。
 *     狙いの変更の知らせ({@link LivingChangeTargetEvent} = Goal の setTarget も Brain の StartAttacking も通る)で、変わった Mob と植物側が
 *     互いを狙うのを止める(ほかの MOD の Mob にも効く)。</li>
 *     <li><b>死んだとき</b>(★): 萌芽体は生えない。飛び火は起こす。破裂は {@code corpse/PlantSideBurst}。ドロップは元のまま。</li>
 *     <li><b>変身しない</b>(★): バニラの変身(雷・ゾンビ化・溺れ)を止める。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class GenericInfection {

    private GenericInfection() {
    }

    /** ボスの最大体力の修飾の id(★ 0.6 倍)。 */
    public static final ResourceLocation BOSS_HEALTH_ID = OvergrownVegetation.id("generic_infection_boss");

    public static GenericInfectionSpec spec() {
        return DesignData.sporeField().genericInfection();
    }

    /** 変わったか(変わっている途中も含む)。 */
    public static boolean isTurned(Entity entity) {
        return entity.hasData(ModAttachments.GENERIC_INFECTED) && entity.getData(ModAttachments.GENERIC_INFECTED).turned;
    }

    /** 変わる動きの途中か。 */
    public static boolean isTransforming(Entity entity) {
        return entity.hasData(ModAttachments.GENERIC_INFECTED)
                && entity.getData(ModAttachments.GENERIC_INFECTED).transforming(entity.level().getGameTime());
    }

    /**
     * 汎用の感染体になれる Mob か: 生きている Mob(プレイヤー以外)・まだ植物側でない・専用の G1 が無い({@link G1Species#ofVictim} が無い。
     * 表の 13 種の子どもとウォーデンはなれる ★)・{@code excludedEntities} に無い。
     */
    public static boolean canTurn(LivingEntity entity) {
        return entity.isAlive() && canTurnIgnoringLife(entity);
    }

    private static boolean canTurnIgnoringLife(LivingEntity entity) {
        GenericInfectionSpec s = spec();
        if (!s.enabled() || !(entity instanceof Mob) || entity instanceof Player || entity.isRemoved()) return false;
        if (SporeExposureTracker.isPlantSide(entity) || G1Species.ofVictim(entity) != null) return false;
        return !s.excludedEntities().contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
    }

    /** ボスとして扱う種か(タグ {@code c:bosses} か、{@code bossEntities})。 */
    public static boolean isBossType(LivingEntity entity) {
        return entity.getType().is(Tags.EntityTypes.BOSSES)
                || spec().bossEntities().contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
    }

    /**
     * 変える。{@code instant} なら変わる動きを飛ばす(確かめ用)。なれない Mob・もう変わった Mob には何もしない。
     *
     * @return 変えたか
     */
    public static boolean turn(LivingEntity entity, boolean instant) {
        if (!canTurn(entity)) return false;
        doTurn((Mob) entity, instant);
        return true;
    }

    private static void doTurn(Mob mob, boolean instant) {
        GenericInfected g = mob.getData(ModAttachments.GENERIC_INFECTED);
        g.turned = true;
        g.seed = mob.getRandom().nextLong();
        g.startTick = mob.level().getGameTime();
        g.transformTicks = instant ? 0 : spec().transformTicks();
        g.boss = isBossType(mob);
        g.blockedDamage = 0.0F;
        // 感染の後始末: 暴露量・感染・逃げる向きを消し、弱りの修飾を外す(変わった Mob は植物側なので、もう測らない)
        if (mob.hasData(ModAttachments.SPORE_EXPOSURE)) mob.getData(ModAttachments.SPORE_EXPOSURE).reset();
        AttributeInstance speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(MobInfection.WEAKNESS_ID);
        applyHealth(mob, g.boss);
        mob.clearFire();
        GenericInfectionAi.forgetOwner(mob);
        GenericInfectionAi.install(mob);
        releaseAttackers(mob); // 変わる前から狙っていた植物側(殴って感染させた G1・G4 など)の狙いを外す
        turnedCount++;
        sync(mob);
    }

    /** 体力(★ 問い 1・ボス): 胞子の傷を外し、ボスは最大体力を倍率に、満タンにする。 */
    static void applyHealth(Mob mob, boolean boss) {
        AttributeInstance max = mob.getAttribute(Attributes.MAX_HEALTH);
        if (max != null) {
            max.removeModifier(SporeExposureTracker.SPORE_SCAR_ID);
            max.removeModifier(BOSS_HEALTH_ID);
            if (boss && spec().bossMaxHealthFactor() < 1.0D) {
                max.addPermanentModifier(new AttributeModifier(BOSS_HEALTH_ID, spec().bossMaxHealthFactor() - 1.0D,
                        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
        }
        mob.setHealth(mob.getMaxHealth());
    }

    /** 戻す(確かめ用)。変わっていなければ何もしない。ボスの体力の修飾は外す(胞子の傷は戻さない)。 */
    public static boolean revert(LivingEntity entity) {
        if (!isTurned(entity)) return false;
        entity.getData(ModAttachments.GENERIC_INFECTED).clear();
        AttributeInstance max = entity.getAttribute(Attributes.MAX_HEALTH);
        if (max != null && max.getModifier(BOSS_HEALTH_ID) != null) {
            max.removeModifier(BOSS_HEALTH_ID);
            entity.setHealth(entity.getMaxHealth());
        }
        if (entity instanceof Mob mob) GenericInfectionAi.uninstall(mob);
        sync(entity);
        return true;
    }

    // ---------------------------------------------------------------- 変わる所(感染の死を止める)

    /**
     * 感染の死(★ 問い 2: G1 に殴り殺されたのも)で、なれる Mob なら死を止めて変える。優先度 HIGH: 飛び火({@code MobInfection#onDeath})・
     * G1 の死体と萌芽体({@code G1Infection}、LOW)・破裂より前に入り、止めればそれらは起きない。死の声はこの前にもう鳴っている(バニラの順)。
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onDeathTurn(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        LivingEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level) || entity instanceof Player) return;
        if (isTurned(entity) || level.getDifficulty() == Difficulty.PEACEFUL) return;
        if (!G1Infection.isInfectionDeath(entity, event.getSource()) || !canTurnIgnoringLife(entity)) return;
        event.setCanceled(true);
        entity.setHealth(1.0F); // 死なずに済んだ(この後 applyHealth で満タン)
        doTurn((Mob) entity, false);
    }

    /**
     * 胞子の 20 ダメージが弾かれた(胞子のダメージの種類に耐性を持つ Mob。Cataclysm のコボレディエーター・ワジェットなど。2026-11-15 ユーザー決定
     * 「耐性を無視して胞子で変える」)。入ったことにして弾かれた量を足し、その合計が今の体力(+ 衝撃吸収)に届いたら変える【仮】
     * = 普通の Mob が削られて死ぬのと同じ回数で変わる(G2 の決まりと揃える。胞子の傷 = 最大体力の減りは付けられないので付けない)。
     * 完全な無敵(NBT の Invulnerable)の Mob・ピースフル・なれない Mob は数えない(呼ぶ側は今までどおり、次の判定でまた試す)。
     *
     * @return 数えた(呼ぶ側は暴露量を 0 に戻す)
     */
    public static boolean onSporeHitBlocked(ServerLevel level, LivingEntity entity, float amount) {
        if (entity.isInvulnerable() || level.getDifficulty() == Difficulty.PEACEFUL || !canTurn(entity)) return false;
        GenericInfected g = entity.getData(ModAttachments.GENERIC_INFECTED);
        g.blockedDamage += amount;
        blockedCount++;
        if (g.blockedDamage >= entity.getHealth() + entity.getAbsorptionAmount()) doTurn((Mob) entity, false);
        return true;
    }

    /** 数え(確かめ用): 弾かれた胞子のダメージを数えた回数。 */
    public static int blockedCount;

    /** 変わった Mob が死んだ(★ 問い 11): 飛び火を起こす(萌芽体は isPlantSide で生えない・破裂は PlantSideBurst)。 */
    @SubscribeEvent
    public static void onTurnedDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        LivingEntity entity = event.getEntity();
        if (entity.level() instanceof ServerLevel level && isTurned(entity)) {
            MobInfection.jumpAt(level, entity.blockPosition());
        }
    }

    /** ★ 問い 14: 変わった Mob はバニラの変身(雷でウィッチ・豚 → ゾンビピグリン・溺れて・ゾンビ化など)をしない。 */
    @SubscribeEvent
    public static void onConvert(LivingConversionEvent.Pre event) {
        if (isTurned(event.getEntity())) {
            event.setCanceled(true);
            event.setConversionTimer(1200); // 止めるだけだと水の中のハスクなどが毎 tick 試し直すので、時計も戻す
        }
    }

    /** 変わった Mob と植物側が互いを狙うのを止める(Goal の setTarget も Brain の StartAttacking も通る)。null にするのは止めない。 */
    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        guardTarget(event);
    }

    /** 見張りの間隔(tick)と半径(ブロック)【仮】。 */
    static final int RELEASE_INTERVAL = 60; // 11-16 コードの見直し ③10: 10 → 60(変わった直後の外しと、殴られた瞬間の外し = ParasitizedAttackInfectionHandler があるので保険の役)
    static final double RELEASE_RADIUS = 48.0D;

    /**
     * 変わった Mob を狙っている植物側の狙いを外す(Goal の狙い・Brain の ATTACK_TARGET・殴られた相手の覚え)。変わったとき + 見張りの間隔ごと。
     * 狙いの変更の知らせ({@link #onChangeTarget})は新しく狙うときしか止められない —— 殴って感染させた相手が変わっても、握った狙いは残るため
     * (11-15 ユーザーの不具合「感染後の Mob が依然として G1、4〜 に敵対され続けている」)。
     *
     * @return 外した数
     */
    public static int releaseAttackers(Mob turned) {
        if (!(turned.level() instanceof ServerLevel level)) return 0;
        int n = 0;
        for (Mob m : level.getEntitiesOfClass(Mob.class, turned.getBoundingBox().inflate(RELEASE_RADIUS),
                m -> m != turned && SporeExposureTracker.isPlantSide(m))) {
            boolean hit = false;
            if (m.getTarget() == turned) {
                m.setTarget(null);
                hit = true;
            }
            var brain = m.getBrain();
            if (brain.checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED)
                    && brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == turned) {
                brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
                hit = true;
            }
            if (m.getLastHurtByMob() == turned) m.setLastHurtByMob(null);
            if (hit) n++;
        }
        return n;
    }

    /**
     * 植物側と変わった Mob の間のダメージを止める(狙いを通さない技・範囲攻撃・飛び道具・破裂も)。攻め手は {@code getSource().getEntity()}
     * (飛び道具なら撃った Mob)。自分で自分を傷付けるのは止めない。
     */
    @SubscribeEvent
    public static void onIncomingDamage(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide() || !(event.getSource().getEntity() instanceof LivingEntity attacker) || attacker == victim) return;
        boolean victimTurned = isTurned(victim), attackerTurned = isTurned(attacker);
        if (!victimTurned && !attackerTurned) return;
        if ((victimTurned && SporeExposureTracker.isPlantSide(attacker)) || (attackerTurned && SporeExposureTracker.isPlantSide(victim))) {
            event.setCanceled(true);
            // 変わった Mob を殴った植物側の狙いを、その場で外す(2026-11-16 コードの見直し ③10。見張り releaseAttackers は 60 tick おきの保険に下げた)
            if (victimTurned && attacker instanceof Mob m) releaseOne(m, victim);
        }
    }

    /** {@code m}(植物側)が {@code turned} を狙っていたら外す(Goal の狙い・Brain の ATTACK_TARGET・殴られた覚え)。外したら true。 */
    static boolean releaseOne(Mob m, LivingEntity turned) {
        boolean hit = false;
        if (m.getTarget() == turned) {
            m.setTarget(null);
            hit = true;
        }
        var brain = m.getBrain();
        if (brain.checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED)
                && brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == turned) {
            brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
            hit = true;
        }
        if (m.getLastHurtByMob() == turned) m.setLastHurtByMob(null);
        return hit;
    }

    private static void guardTarget(LivingChangeTargetEvent event) {
        LivingEntity target = event.getNewAboutToBeSetTarget();
        if (target == null) return;
        LivingEntity self = event.getEntity();
        boolean selfTurned = isTurned(self), targetTurned = isTurned(target);
        if (!selfTurned && !targetTurned) return;
        if ((selfTurned && SporeExposureTracker.isPlantSide(target)) || (targetTurned && SporeExposureTracker.isPlantSide(self))) {
            event.setCanceled(true);
        }
    }

    // ---------------------------------------------------------------- 毎 tick・読み直し

    /** 区画の読み直し・次元の移動で入り直した変わった Mob に、動きを付け直す(元の Goal は新しく作られている)。 */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob) || !isTurned(mob)) return;
        GenericInfectionAi.install(mob);
    }

    @SubscribeEvent
    public static void onTickPost(EntityTickEvent.Post event) {
        Entity e = event.getEntity();
        if (e instanceof Mob mob && !e.level().isClientSide() && isTurned(e) && mob.isAlive()) GenericInfectionAi.afterTick(mob);
    }

    @SubscribeEvent
    public static void onTick(EntityTickEvent.Pre event) {
        Entity e = event.getEntity();
        if (!(e instanceof Mob mob) || e.level().isClientSide() || !e.hasData(ModAttachments.GENERIC_INFECTED)) return;
        GenericInfected g = e.getData(ModAttachments.GENERIC_INFECTED);
        if (!g.turned || !mob.isAlive()) return;
        long now = e.level().getGameTime();
        if (g.transforming(now)) {
            hold(mob);
            return;
        }
        if ((mob.tickCount & 15) == 0) GenericInfectionAi.keepOwnerless(mob);
        if (Math.floorMod(mob.tickCount + mob.getId(), RELEASE_INTERVAL) == 0) releaseAttackers(mob);
        if (Math.floorMod(mob.tickCount + mob.getId(), GenericInfectionAi.ACQUIRE_INTERVAL) == 0) GenericInfectionAi.acquireIfIdle(mob, g);
        if (Math.floorMod(mob.tickCount + mob.getId(), 5) == 0) GenericInfectionAi.feedBrain(mob); // Brain の Mob に狙いを伝える(★ 問い 7 = 1)
        GenericInfectionAi.trackTarget(mob, g);
        if (GenericInfectionAi.rammingNow(mob)) GenericInfectionAi.ramTick(mob, g, now);
        if (now < g.callUntil) marchTick(mob, g);
    }

    // ---------------------------------------------------------------- 監視塔の呼び寄せ(★ G7: 司令塔には乗せず、呼び寄せにだけ応じる)

    /** 呼び寄せの口({@code director/PlantSideCall#towards} から)。半径の中の変わり終えた Mob を、印の相手の居場所へ {@code ticks} の間向かわせる。 */
    public static int call(ServerLevel level, net.minecraft.world.phys.Vec3 at, LivingEntity target, double radius, int ticks) {
        int n = 0;
        long now = level.getGameTime();
        for (Mob m : level.getEntitiesOfClass(Mob.class, new net.minecraft.world.phys.AABB(at, at).inflate(radius, 64.0D, radius), GenericInfection::isTurned)) {
            if (!m.isAlive() || isTransforming(m) || m.distanceToSqr(at) > radius * radius) continue;
            GenericInfected g = m.getData(ModAttachments.GENERIC_INFECTED);
            g.callX = target.getX();
            g.callY = target.getY();
            g.callZ = target.getZ();
            g.callUntil = now + Math.max(1, ticks);
            n++;
        }
        return n;
    }

    /** 呼ばれた先へ歩く(狙う相手がいる間はそちらが先。着いたらやめる)。歩けない Mob は少し寄せる。 */
    private static void marchTick(Mob mob, GenericInfected g) {
        if (GenericInfectionAi.targetOf(mob) != null) return;
        double dx = g.callX - mob.getX(), dz = g.callZ - mob.getZ();
        if (dx * dx + dz * dz < 16.0D) {
            g.callUntil = Long.MIN_VALUE / 2;
            return;
        }
        if (mob instanceof net.minecraft.world.entity.PathfinderMob) {
            if ((mob.tickCount % 20) == 0 || mob.getNavigation().isDone()) mob.getNavigation().moveTo(g.callX, g.callY, g.callZ, 1.0D);
        } else {
            net.minecraft.world.phys.Vec3 to = new net.minecraft.world.phys.Vec3(g.callX, g.callY + 1.0D, g.callZ).subtract(mob.position()).normalize();
            mob.setDeltaMovement(mob.getDeltaMovement().scale(0.9D).add(to.scale(0.05D)));
        }
    }

    /** 呼び寄せに向かっている途中か(確かめ用)。 */
    public static boolean isCalled(Entity entity) {
        return entity.hasData(ModAttachments.GENERIC_INFECTED) && entity.level().getGameTime() < entity.getData(ModAttachments.GENERIC_INFECTED).callUntil;
    }

    /** 変わる間の止め: ナビ・狙い・水平の動き・Brain の歩く先と狙い(Brain は止める間だけ消す)。 */
    private static void hold(Mob mob) {
        mob.getNavigation().stop();
        if (mob.getTarget() != null) mob.setTarget(null);
        mob.setDeltaMovement(0.0D, Math.min(0.0D, mob.getDeltaMovement().y), 0.0D);
        mob.setXxa(0.0F);
        mob.setZza(0.0F);
        mob.setJumping(false);
        var brain = mob.getBrain();
        if (brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)) brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        if (brain.checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED)) brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
    }

    // ---------------------------------------------------------------- 同期

    /** 印の今の様子を、その Mob を追跡しているプレイヤー全員へ送る。 */
    public static void sync(Entity entity) {
        if (entity.level().isClientSide()) return;
        PacketDistributor.sendToPlayersTrackingEntity(entity, payloadOf(entity));
    }

    /** 送る中身(変わっていなければ「消す」)。 */
    public static GenericInfectionPayload payloadOf(Entity entity) {
        if (!isTurned(entity)) return GenericInfectionPayload.removed(entity.getId());
        GenericInfected g = entity.getData(ModAttachments.GENERIC_INFECTED);
        return new GenericInfectionPayload(entity.getId(), true, g.seed, g.startTick, g.transformTicks, g.boss);
    }

    /** 追跡を始めた(Mob を出すパケットの後に来る。{@code ServerEntity#addPairing})→ 変わった Mob ならそのプレイヤーへ送る。 */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        Entity target = event.getTarget();
        if (event.getEntity() instanceof ServerPlayer sp && isTurned(target)) {
            PacketDistributor.sendToPlayer(sp, payloadOf(target));
        }
    }

    /**
     * 植物側の決まり({@code spore/PlantSideRules}。死の破裂・攻撃の感染)へ「変わった印」の問い合わせを差し込む
     * (11-15 ユーザー決定: 汎用の感染体も死んだら破裂する。破裂そのものは向こうのセッションが作った)。
     */
    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Setup {
        @SubscribeEvent
        public static void onCommonSetup(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent event) {
            // 共通の準備は並列のスレッドで走るので、ただの ArrayList への登録は主スレッドへ回す
            event.enqueueWork(() -> {
                com.yayuy.overgrownvegetation.spore.PlantSideRules.TRANSFORMED = GenericInfection::isTurned;
                com.yayuy.overgrownvegetation.director.PlantSideCall.registerListener(GenericInfection::call);
            });
        }

        private Setup() {
        }
    }

    /** 数え(/ov perf infection)。起動してから変えた回数。 */
    public static int turnedCount;
}
