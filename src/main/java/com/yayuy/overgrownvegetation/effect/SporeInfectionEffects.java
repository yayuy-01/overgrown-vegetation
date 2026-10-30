package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EffectTable;
import com.yayuy.overgrownvegetation.entity.BlightFernPlacement;
import com.yayuy.overgrownvegetation.item.ProtectiveMaskItem;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;

/**
 * 「胞子感染」({@link ModMobEffects#SPORE_INFECTION})を実際に付与・進行させる処理の一本化。
 *
 * <h2>プレイヤー —— 10 段階(2026-10-14 ユーザー指定で作り直し)</h2>
 * <ul>
 *     <li>amplifier = 段階 − 1(0〜9)。持続は無限で、段階の進みは {@link ModAttachments#SPORE_STAGE_TICKS} に数える。
 *     {@code stageTicks}(10 分)ごとに 1 段上がり、感染10 からさらに 10 分で死んで汚染シダになる({@link #killByInfection})。</li>
 *     <li>段階が上がるたびに {@code damagePerStage}(1)のダメージ。最大体力は治るまで {@code maxHealthPerStage}(1)× (段階 − 1) 減る
 *     (かかった瞬間の感染1 では減らない。「段階が上がるごとに」の読み。Claude の解釈)。</li>
 *     <li>かかるのは「胞子濃度 50% 以上に 30 秒」({@link SporeInfectionTracker})と「過繁茂した Mob の攻撃で 30%」({@link #plantAttack})。</li>
 *     <li>進行は濃度 50% 以上の場所で +5%、過繁茂したバイオームで +10% 速く、敵の攻撃を受けると 5% の確率で 10 秒進む。</li>
 *     <li><b>防護マスク</b>: かぶっている間はかからない。もうかかっていれば、場所・攻撃による加速は効かない(10 分ごとの昇格はそのまま)。</li>
 *     <li>牛乳では治らない({@link SporeInfectionMobEffect#fillEffectCures})。治す手段は後で作る霊薬。</li>
 * </ul>
 *
 * <h2>Mob —— 今までどおり</h2>
 * <p>amplifier 0〜2 で {@code effects.json} の {@code level1〜3}({@link EffectTable#level})。持続が切れると死ぬ
 * ({@link SporeInfectionExpiryHandler})。{@link ParasitizedMob} には一切付与しない。
 */
public final class SporeInfectionEffects {

    /** 胞子感染で下がる防御力・移動速度・最大体力の修飾の id(効果そのものには持たせず、ここで張る)。 */
    private static final ResourceLocation ARMOR_ID = OvergrownVegetation.id("effect.spore_infection.armor");
    private static final ResourceLocation MOVE_ID = OvergrownVegetation.id("effect.spore_infection.movement_speed");
    private static final ResourceLocation MAX_HEALTH_ID = OvergrownVegetation.id("effect.spore_infection.max_health");

    /**
     * {@code target} を胞子感染させる。
     * <ul>
     *     <li>Mob: amplifier(0=胞子感染1 … 2=胞子感染3)をそのまま。低い段階はかからない・同じ段階なら延長。</li>
     *     <li>プレイヤー: 段階は無視して<b>感染1 からかかる</b>(もうかかっていれば何もしない)。マスクをかぶっていればかからない。
     *     確率で決まるきっかけ(Mob の攻撃)は呼ぶ側で振る({@link #plantAttack})。</li>
     * </ul>
     *
     * @return 新しくかかった・延長したら true
     */
    public static boolean infect(LivingEntity target, int amplifier) {
        if (target instanceof ParasitizedMob) {
            return false;
        }
        if (target instanceof Player player) {
            return startPlayer(player);
        }
        return applyMob(target, amplifier);
    }

    /**
     * 過繁茂した Mob に攻撃された(殴る・胞子をかけるなど)。プレイヤーなら、未感染は {@code attackInfectChance}(30%)でかかり、
     * 感染中は {@code attackAdvanceChance}(5%)で進行が {@code attackAdvanceTicks}(10 秒)進む(マスクがあればどちらも無し)。
     * Mob なら今までどおり {@code mobAmplifier} でかかる。
     */
    public static void plantAttack(LivingEntity target, int mobAmplifier) {
        if (target instanceof ParasitizedMob) {
            return;
        }
        if (!(target instanceof Player player)) {
            infect(target, mobAmplifier);
            return;
        }
        if (ProtectiveMaskItem.isWearing(player)) {
            return;
        }
        EffectTable.PlayerInfectionSpec spec = DesignData.effects().playerInfection();
        if (stageOf(player) == 0) {
            if (player.getRandom().nextDouble() < attackInfectChance(player)) {
                startPlayer(player);
            }
        } else if (player.getRandom().nextDouble() < spec.attackAdvanceChance()) {
            advance(player, spec.attackAdvanceTicks());
        }
    }

    /** 過繁茂した Mob の攻撃でかかる確率。布鎧(追加防具・体)を着ていれば 15%、着ていなければ 30%(2026-10-15 ユーザー指定)。 */
    public static double attackInfectChance(Player player) {
        EffectTable.PlayerInfectionSpec spec = DesignData.effects().playerInfection();
        return com.yayuy.overgrownvegetation.item.GambesonItem.isWorn(player)
                ? spec.gambesonAttackInfectChance() : spec.attackInfectChance();
    }

    /**
     * デバッグ用({@link com.yayuy.overgrownvegetation.item.SporeInfectionSyringeItem})。今の段階を取り除いてから付け直す
     * (プレイヤーは段階 = {@code amplifier} + 1、マスクを無視)。
     */
    public static boolean forceSet(LivingEntity target, int amplifier) {
        if (target instanceof ParasitizedMob) {
            return false;
        }
        if (target instanceof Player player) {
            setPlayerStage(player, amplifier + 1);
            return true;
        }
        target.removeEffect(ModMobEffects.SPORE_INFECTION);
        return applyMob(target, amplifier);
    }

    /** 段階を<b>下げる</b>(Mob 用。低い段階は {@link #infect} が拒むので、取り除いてから付け直す)。 */
    public static boolean downgrade(LivingEntity target, int amplifier) {
        if (target instanceof ParasitizedMob || target instanceof Player) {
            return false;
        }
        target.removeEffect(ModMobEffects.SPORE_INFECTION);
        return infect(target, amplifier);
    }

    /** 今の「胞子感染」の amplifier。未感染なら -1。 */
    public static int levelOf(LivingEntity entity) {
        MobEffectInstance current = entity.getEffect(ModMobEffects.SPORE_INFECTION);
        return current == null ? -1 : current.getAmplifier();
    }

    /** 今の段階(1〜)。未感染なら 0。 */
    public static int stageOf(LivingEntity entity) {
        return levelOf(entity) + 1;
    }

    // ------------------------------------------------------------------------------------------------
    // プレイヤー
    // ------------------------------------------------------------------------------------------------

    /** 未感染のプレイヤーを感染1 にする(マスクをかぶっていればかからない)。 */
    public static boolean startPlayer(Player player) {
        if (stageOf(player) > 0 || ProtectiveMaskItem.isWearing(player)) {
            return false;
        }
        setPlayerStage(player, 1);
        return true;
    }

    /**
     * 段階を 1 つ上げる(未感染なら感染1 から)。感染10 から上げると死ぬ。段階が上がったら {@code damagePerStage} のダメージ。
     * 大きな未発達な果実・胞子腺の罰・10 分の満了から。
     */
    public static void raisePlayerStage(Player player) {
        int stage = stageOf(player);
        if (stage == 0) {
            setPlayerStage(player, 1);
            return;
        }
        EffectTable.PlayerInfectionSpec spec = DesignData.effects().playerInfection();
        if (stage >= spec.maxStage()) {
            if (player.level() instanceof ServerLevel level) {
                killByInfection(level, player);
            }
            return;
        }
        // 暖花の丸薬の守りがあれば、この 1 回はダメージも最大体力の低下も無し(2026-10-16、MedicineEffects)。
        boolean guarded = MedicineEffects.consumeStageGuard(player);
        setPlayerStage(player, stage + 1);
        if (!guarded && player.level() instanceof ServerLevel level && spec.damagePerStage() > 0.0D) {
            player.hurt(ModDamageTypes.sporeInfection(level), (float) spec.damagePerStage());
        }
    }

    /** 段階を {@code stage}(1〜10。0 以下なら治す)にし、進みを 0 に戻す。 */
    public static void setPlayerStage(Player player, int stage) {
        EffectTable.PlayerInfectionSpec spec = DesignData.effects().playerInfection();
        player.setData(ModAttachments.SPORE_STAGE_TICKS, 0);
        if (stage <= 0) {
            player.removeEffect(ModMobEffects.SPORE_INFECTION);
            player.setData(ModAttachments.SPORE_HEALTH_RELIEF, 0);
            syncModifiers(player);
            return;
        }
        int clamped = Math.min(stage, spec.maxStage());
        MobEffectInstance current = player.getEffect(ModMobEffects.SPORE_INFECTION);
        if (current == null || current.getAmplifier() != clamped - 1) {
            player.removeEffect(ModMobEffects.SPORE_INFECTION);
            player.addEffect(new MobEffectInstance(ModMobEffects.SPORE_INFECTION, MobEffectInstance.INFINITE_DURATION,
                    clamped - 1, false, true, true));
        }
        syncModifiers(player);
    }

    /** 進行を {@code ticks} 進める。{@code stageTicks} に届いたら段階を上げる(届いた分だけ何段でも)。 */
    public static void advance(Player player, int ticks) {
        if (ticks <= 0 || stageOf(player) == 0 || MedicineEffects.progressPaused(player)) { // 進行止めの丸薬(2026-10-16)
            return;
        }
        int stageTicks = DesignData.effects().playerInfection().stageTicks();
        int progress = player.getData(ModAttachments.SPORE_STAGE_TICKS) + ticks;
        while (progress >= stageTicks && stageOf(player) > 0 && player.isAlive()) {
            progress -= stageTicks;
            raisePlayerStage(player); // 段階を上げると進みは 0 に戻るので、残りは下で書き直す
        }
        if (stageOf(player) > 0 && player.isAlive()) {
            player.setData(ModAttachments.SPORE_STAGE_TICKS, progress);
        }
    }

    /** 今の段階に入ってから進んだ tick。 */
    public static int progressOf(Player player) {
        return player.getData(ModAttachments.SPORE_STAGE_TICKS);
    }

    /** 胞子感染で死なせ、死んだ場所に汚染シダを生やす。 */
    public static void killByInfection(ServerLevel level, LivingEntity entity) {
        BlockPos deathPos = entity.blockPosition();
        entity.hurt(ModDamageTypes.sporeInfection(level), Float.MAX_VALUE);
        if (entity.isDeadOrDying() && level.isLoaded(deathPos)) {
            // 近くに汚染シダがもう十分いれば生やさない(2026-09-25、密度の上限)。
            BlightFernPlacement.spawnFromDeath(level, deathPos);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // 症状の属性(防御力・移動速度・最大体力)
    // ------------------------------------------------------------------------------------------------

    /**
     * 今の感染に合わせて防御力・移動速度・最大体力の修飾を張り直す(感染していなければ外す)。
     * 効果そのもの({@link SporeInfectionMobEffect})に持たせないのは、同じ amplifier 0 でも
     * プレイヤー(感染1 = 防御そのまま)と Mob(胞子感染1 = 防御 −20%)で値が違うため。
     */
    public static void syncModifiers(LivingEntity entity) {
        int amp = levelOf(entity);
        double armor = 0.0D;
        double move = 0.0D;
        double maxHealth = 0.0D;
        if (amp >= 0) {
            if (entity instanceof Player) {
                EffectTable.PlayerInfectionSpec spec = DesignData.effects().playerInfection();
                // 気付け薬は症状(防御の低下)だけ抑える。最大体力は暖花の丸薬で免れた段を引く(2026-10-16、MedicineEffects)。
                armor = MedicineEffects.symptomsSuppressed(entity) ? 0.0D : spec.stage(amp + 1).armorReduction();
                maxHealth = spec.maxHealthPerStage() * (amp - MedicineEffects.healthRelief((Player) entity, amp));
            } else {
                EffectTable.LevelSpec spec = DesignData.effects().level(amp);
                armor = spec.armorReduction();
                move = spec.moveReduction();
            }
        }
        apply(entity, Attributes.ARMOR, ARMOR_ID, -armor, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        apply(entity, Attributes.MOVEMENT_SPEED, MOVE_ID, -move, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        apply(entity, Attributes.MAX_HEALTH, MAX_HEALTH_ID, -maxHealth, AttributeModifier.Operation.ADD_VALUE);
    }

    private static void apply(LivingEntity entity, Holder<Attribute> attribute, ResourceLocation id, double amount,
                              AttributeModifier.Operation operation) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        AttributeModifier existing = instance.getModifier(id);
        if (Math.abs(amount) < 1.0E-6D) {
            if (existing != null) {
                instance.removeModifier(id);
            }
            return;
        }
        if (existing != null && Math.abs(existing.amount() - amount) < 1.0E-6D) {
            return;
        }
        instance.addOrUpdateTransientModifier(new AttributeModifier(id, amount, operation));
        if (attribute == Attributes.MAX_HEALTH && entity.getHealth() > entity.getMaxHealth()) {
            entity.setHealth(entity.getMaxHealth());
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Mob
    // ------------------------------------------------------------------------------------------------

    private static boolean applyMob(LivingEntity target, int amplifier) {
        int amp = Math.max(0, Math.min(EffectTable.MAX_LEVEL_INDEX, amplifier));
        MobEffectInstance current = target.getEffect(ModMobEffects.SPORE_INFECTION);
        if (current != null && current.getAmplifier() > amp) {
            // 既に持っている段階より低い段階はかからない。
            return false;
        }
        EffectTable.LevelSpec spec = DesignData.effects().level(amp);
        boolean applied = target.addEffect(new MobEffectInstance(ModMobEffects.SPORE_INFECTION, spec.mobDurationTicks(), amp));
        if (applied) {
            syncModifiers(target);
            if (spec.vanillaHungerDurationTicks() > 0 && target.level() instanceof ServerLevel) {
                target.addEffect(new MobEffectInstance(MobEffects.HUNGER, spec.vanillaHungerDurationTicks(),
                        Math.max(0, spec.vanillaHungerAmplifier())));
            }
        }
        return applied;
    }

    private SporeInfectionEffects() {
    }
}
