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
 *     <li>amplifier = 段階 − 1(0〜9)。持続は無限で、進み(%秒)は {@link ModAttachments#SPORE_STAGE_TICKS}(名前は前の「tick」の名残)。
 *     今の段の量(段ごとに +{@code stageDoseGrowth}、後の段ほど多い)たまるごとに 1 段上がり、感染10 からさらに 1 段ぶんたまると死に、その場に萌芽体が生える({@link #killByInfection})。</li>
 *     <li>上がるたびに {@code damagePerStage}(1)のダメージ。最大体力は治るまで {@code maxHealthPerStage} × (段階 − 1) 減る。</li>
 *     <li>かかるのは暴露量が 1500 %秒に届いたとき({@code spore/SporeExposureTracker}、11-04)と、植物側の Mob の傷で 30%({@link #plantAttack})。</li>
 *     <li>進み(11-15 ユーザー決定「混ぜた形」): 体の中でゆっくり育つ分({@code growStageSeconds} で 1 段、疲労で ×1.5 まで)+ 息で浴びた胞子
 *     ({@link #tickInfected})+ 傷の暴露・G1 の胞子の技({@link #advance} をその場で)。下がらない。場所の倍率と感染中の被弾 5% の抽選は外した。</li>
 *     <li><b>防護マスク</b>(フィルターが残っている間): 息で浴びる分だけ止める(未感染の暴露量・感染中の進みとも)。傷の分は止めない。</li>
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

    /**
     * 未感染のプレイヤーを感染1 にする。マスクは見ない(11-15: マスクが止めるのは息で浴びる分だけで、それは暴露量を積む所で止めている。
     * 傷の暴露・傷の抽選でかかるのは止めない)。
     */
    public static boolean startPlayer(Player player) {
        if (stageOf(player) > 0) {
            return false;
        }
        setPlayerStage(player, 1);
        return true;
    }

    /**
     * 植物側の Mob に攻撃された。未感染は {@code attackInfectChance}(30%)でかかる(傷から入るのでマスクでは防げない、11-15)。
     * 感染中の 5% の抽選(10 秒進む)は 11-15 に傷の暴露({@code ParasitizedAttackInfectionHandler#addWoundDose})へ置き換えた。
     */
    public static void plantAttack(Player player) {
        EffectTable.PlayerInfectionSpec spec = DesignData.effects().playerInfection();
        if (stageOf(player) == 0 && player.getRandom().nextDouble() < spec.attackInfectChance()) {
            startPlayer(player);
        }
    }

    /**
     * 感染中のプレイヤーの判定 1 回({@code interval} tick ぶん)。経験値を吸い、段の進みを足す(11-15 の混ぜた形):
     * 体の中で育つ分 {@code growPerSecond} × 疲労の倍率(×1.5 まで)+ 息で浴びた分 {@code breathedPerSecond}(マスクが効いていれば呼ぶ側が 0 を渡す)。
     * 傷・G1 の胞子の技の分は、その場で {@link #advance} が足す。
     *
     * @param breathedPerSecond 息で浴びている胞子(%秒 / 秒。濃度 5% 以上ならその濃度、それ未満なら閉じた部屋・汚れた装備から染みる分)
     */
    public static void tickInfected(Player player, SporeExposure ex, int interval, double breathedPerSecond) {
        EffectTable.PlayerInfectionSpec spec = DesignData.effects().playerInfection();
        ex.xpDrainTicks += interval;
        if (ex.xpDrainTicks >= spec.xpDrainIntervalTicks()) {
            ex.xpDrainTicks -= spec.xpDrainIntervalTicks();
            if (spec.xpDrainPoints() > 0 && (player.totalExperience > 0 || player.experienceLevel > 0
                    || player.experienceProgress > 0.0F)) {
                player.giveExperiencePoints(-spec.xpDrainPoints());
            }
        }
        // 体の中でゆっくり育つ(11-15)。疲労で進みやすくなる(2026-11-13 サバイバル段 1。上限 fatigue.infectionMultiplierMax)のはこちらに掛ける
        double seconds = interval / 20.0D;
        double grow = spec.growPerSecond(stageOf(player)) * com.yayuy.overgrownvegetation.survival.FatigueTracker.infectionMultiplier(player);
        advance(player, (grow + Math.max(0.0D, breathedPerSecond)) * seconds);
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

    /**
     * 段の進みを {@code amount}(%秒)進める(11-15: 進みの単位は tick から暴露量へ。添付 {@code SPORE_STAGE_TICKS} の整数に %秒 を入れ、
     * 端数は {@code SporeExposure#rateCarry} に持ち越す)。今の段の量({@link #stageDoseOf}、後の段ほど多い)に届いたら段階を上げる(届いた分だけ何段でも)。下がらない。
     * 未感染なら何もしない。
     */
    public static void advance(Player player, double amount) {
        if (!(amount > 0.0D) || stageOf(player) == 0) {
            return;
        }
        SporeExposure ex = player.getData(ModAttachments.SPORE_EXPOSURE);
        EffectTable.PlayerInfectionSpec spec = DesignData.effects().playerInfection();
        double exact = player.getData(ModAttachments.SPORE_STAGE_TICKS) + ex.rateCarry + amount;
        long whole = (long) Math.floor(exact);
        ex.rateCarry = exact - whole;
        // 1 段の量は今の段で決まる(後の段ほど多く要る、11-15)。上がるたびに次の段の量で比べ直す
        while (stageOf(player) > 0 && player.isAlive() && whole >= stageDoseOf(spec, stageOf(player))) {
            whole -= stageDoseOf(spec, stageOf(player));
            raisePlayerStage(player); // 段階を上げると進みは 0 に戻るので、残りは下で書き直す
        }
        if (stageOf(player) > 0 && player.isAlive()) {
            player.setData(ModAttachments.SPORE_STAGE_TICKS, (int) Math.min(Integer.MAX_VALUE, whole));
        }
    }

    /** 今の段階に入ってからたまった暴露量(%秒、整数)。 */
    public static int progressOf(Player player) {
        return player.getData(ModAttachments.SPORE_STAGE_TICKS);
    }

    /** 今の段階の進み(0〜1)。未感染は 0。暴露量のバー({@code survival/SurvivalSync})が塗る値。 */
    public static float stageFraction(Player player) {
        if (stageOf(player) <= 0) return 0.0F;
        double dose = stageDoseOf(DesignData.effects().playerInfection(), stageOf(player));
        return (float) Math.max(0.0D, Math.min(1.0D, progressOf(player) / dose));
    }

    /** 段 {@code stage} → stage + 1 に要る暴露量(整数に切り上げ。後の段ほど多い)。 */
    public static int stageDoseOf(EffectTable.PlayerInfectionSpec spec, int stage) {
        return (int) Math.ceil(spec.stageDose(stage));
    }

    /** 胞子感染で死なせ、死んだ場所に萌芽体(BlightFernPlacement#spawnFromDeath。近くに十分いれば生えない)を生やす(10-14 ユーザー指定)。 */
    public static void killByInfection(ServerLevel level, LivingEntity entity) {
        BlockPos deathPos = entity.blockPosition();
        entity.hurt(ModDamageTypes.sporeInfection(level), Float.MAX_VALUE);
        if (entity.isDeadOrDying() && com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, deathPos)) {
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
