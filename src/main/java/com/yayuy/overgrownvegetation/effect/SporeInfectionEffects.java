package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EffectTable;
import com.yayuy.overgrownvegetation.entity.BlightFernPlacement;
import com.yayuy.overgrownvegetation.item.ProtectiveMaskItem;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import com.yayuy.overgrownvegetation.spore.SporeExposure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

/**
 * プレイヤーの「胞子感染」10 段階の付与と進行(2026-10-14 ユーザー指定、11-04 に parked から戻して書き直した)。
 *
 * <ul>
 *     <li>amplifier = 段階 − 1(0〜9)。持続は無限で、進みは {@link ModAttachments#SPORE_STAGE_TICKS}。
 *     {@code stageTicks}(10 分)ごとに 1 段上がり、感染10 からさらに 10 分で死に、その場に萌芽体が生える({@link #killByInfection})。</li>
 *     <li>上がるたびに {@code damagePerStage}(1)のダメージ。最大体力は治るまで {@code maxHealthPerStage} × (段階 − 1) 減る。</li>
 *     <li>かかるのは暴露量が 1500 %秒に届いたとき({@code spore/SporeExposureTracker}、11-04)と、過繁茂の Mob の攻撃で 30%
 *     ({@link #plantAttack}。攻撃する Mob は今いない)。</li>
 *     <li>進みは {@link #tickInfected} が場所の倍率({@link EffectTable.PlayerInfectionSpec#rate})を掛けて足す。</li>
 *     <li><b>防護マスク</b>(フィルターが残っている間): かからない・加速しない(10 分ごとの昇格はそのまま)。</li>
 * </ul>
 * 前の版にあった薬(MedicineEffects)・布鎧・朝霧・ハイヴノードの加速・Mob の 3 段階は退避のまま外した。
 */
public final class SporeInfectionEffects {

    private static final ResourceLocation ARMOR_ID = OvergrownVegetation.id("effect.spore_infection.armor");
    private static final ResourceLocation MAX_HEALTH_ID = OvergrownVegetation.id("effect.spore_infection.max_health");

    /** 今の amplifier。未感染なら -1。 */
    public static int levelOf(LivingEntity entity) {
        MobEffectInstance current = entity.getEffect(ModMobEffects.SPORE_INFECTION);
        return current == null ? -1 : current.getAmplifier();
    }

    /** 今の段階(1〜)。未感染なら 0。 */
    public static int stageOf(LivingEntity entity) {
        return levelOf(entity) + 1;
    }

    /** 未感染のプレイヤーを感染1 にする(フィルターの残った防護マスクをかぶっていればかからない)。 */
    public static boolean startPlayer(Player player) {
        if (stageOf(player) > 0 || ProtectiveMaskItem.isProtecting(player)) {
            return false;
        }
        setPlayerStage(player, 1);
        return true;
    }

    /**
     * 過繁茂した Mob に攻撃された。未感染は {@code attackInfectChance}(30%)でかかり、感染中は {@code attackAdvanceChance}(5%)で
     * 進みが {@code attackAdvanceTicks}(10 秒)進む(マスクが効いていればどちらも無し)。
     */
    public static void plantAttack(Player player) {
        if (ProtectiveMaskItem.isProtecting(player)) {
            return;
        }
        EffectTable.PlayerInfectionSpec spec = DesignData.effects().playerInfection();
        if (stageOf(player) == 0) {
            if (player.getRandom().nextDouble() < spec.attackInfectChance()) {
                startPlayer(player);
            }
        } else if (player.getRandom().nextDouble() < spec.attackAdvanceChance()) {
            advance(player, spec.attackAdvanceTicks());
        }
    }

    /**
     * 感染中のプレイヤーの判定 1 回({@code interval} tick ぶん)。経験値を吸い、場所の倍率を掛けて進める。
     *
     * @param percent        その場の胞子濃度(%)
     * @param overgrownBiome 過繁茂のバイオームにいるか
     * @param masked         防護マスクが効いているか(効いていれば加速しない)
     */
    public static void tickInfected(Player player, SporeExposure ex, int interval, double percent, boolean overgrownBiome,
                                    boolean masked) {
        EffectTable.PlayerInfectionSpec spec = DesignData.effects().playerInfection();
        ex.xpDrainTicks += interval;
        if (ex.xpDrainTicks >= spec.xpDrainIntervalTicks()) {
            ex.xpDrainTicks -= spec.xpDrainIntervalTicks();
            if (spec.xpDrainPoints() > 0 && (player.totalExperience > 0 || player.experienceLevel > 0
                    || player.experienceProgress > 0.0F)) {
                player.giveExperiencePoints(-spec.xpDrainPoints());
            }
        }
        // 疲労で進みやすくなる(2026-11-13 サバイバル段 1。上限 fatigue.infectionMultiplierMax、マスクが効いていれば掛けない)
        double rate = masked ? 1.0D : spec.rate(percent, overgrownBiome)
                * com.yayuy.overgrownvegetation.survival.FatigueTracker.infectionMultiplier(player);
        double exact = interval * rate + ex.rateCarry;
        int ticks = (int) Math.floor(exact);
        ex.rateCarry = exact - ticks;
        advance(player, ticks);
        if (player.isAlive()) {
            syncModifiers(player);
        }
    }

    /** 段階を 1 つ上げる(未感染なら感染1 から)。感染10 から上げると死ぬ。上がったら {@code damagePerStage} のダメージ。 */
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
        setPlayerStage(player, stage + 1);
        if (player.level() instanceof ServerLevel level && spec.damagePerStage() > 0.0D) {
            player.hurt(ModDamageTypes.sporeInfection(level), (float) spec.damagePerStage());
        }
    }

    /** 段階を {@code stage}(1〜10。0 以下なら治す)にし、進みを 0 に戻す。 */
    public static void setPlayerStage(Player player, int stage) {
        EffectTable.PlayerInfectionSpec spec = DesignData.effects().playerInfection();
        player.setData(ModAttachments.SPORE_STAGE_TICKS, 0);
        if (stage <= 0) {
            player.removeEffect(ModMobEffects.SPORE_INFECTION);
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

    /** 進みを {@code ticks} 進める。{@code stageTicks} に届いたら段階を上げる(届いた分だけ何段でも)。 */
    public static void advance(Player player, int ticks) {
        if (ticks <= 0 || stageOf(player) == 0) {
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

    /** 胞子感染で死なせ、死んだ場所に萌芽体(BlightFernPlacement#spawnFromDeath。近くに十分いれば生えない)を生やす(10-14 ユーザー指定)。 */
    public static void killByInfection(ServerLevel level, LivingEntity entity) {
        BlockPos deathPos = entity.blockPosition();
        entity.hurt(ModDamageTypes.sporeInfection(level), Float.MAX_VALUE);
        if (entity.isDeadOrDying() && level.isLoaded(deathPos)) {
            BlightFernPlacement.spawnFromDeath(level, deathPos);
        }
    }

    /**
     * 今の感染に合わせて防御力・最大体力の修飾を張り直す(感染していなければ外す)。効果に持たせないのは、
     * 防御力の低下が段階ごとの表で、最大体力が −(段階 − 1) だから(感染1 では減らない)。
     */
    public static void syncModifiers(LivingEntity entity) {
        int amp = entity instanceof Player ? levelOf(entity) : -1;
        double armor = 0.0D;
        double maxHealth = 0.0D;
        if (amp >= 0) {
            EffectTable.PlayerInfectionSpec spec = DesignData.effects().playerInfection();
            armor = spec.stage(amp + 1).armorReduction();
            maxHealth = spec.maxHealthPerStage() * amp;
        }
        apply(entity, Attributes.ARMOR, ARMOR_ID, -armor, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
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

    private SporeInfectionEffects() {
    }
}
