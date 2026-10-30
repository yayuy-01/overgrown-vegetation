package com.yayuy.overgrownvegetation.variant;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.VariantTable;
import com.yayuy.overgrownvegetation.network.MobVariantPayload;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;

/**
 * 亜種(個体差)の抽選・保存・同期・効果(2026-09-19)。
 *
 * <h2>抽選</h2>
 * <p>{@link EntityJoinLevelEvent}(サーバー側)で、まだ抽選されていない対象 Mob へ
 * {@code variants.json} の重みで種別を割り当てる。対象は {@code eligibleGroups}(既定 1〜4)に属し、
 * {@code excluded} に無く、移動速度が 0 より大きい Mob(汚染シダ・レイドビーコンのような動かない種は外れる)。
 * 種別は {@link ModAttachments#MOB_VARIANT} に保存されるので、一度決まった個体は保存・読み込みをまたいでも変わらない。
 *
 * <h2>同期</h2>
 * <p>テクスチャの切り替えはクライアントで行うので、プレイヤーがその個体を追跡し始めたとき
 * ({@link PlayerEvent.StartTracking})に {@link MobVariantPayload} で種別を送る。
 *
 * <h2>効果</h2>
 * <ul>
 *     <li>硬化: 抽選時に防御値へ永続の乗算修飾を付ける({@link #HARDENED_ARMOR})。</li>
 *     <li>毒性: {@link LivingDamageEvent.Post} で、攻撃者が毒性個体なら相手に毒。</li>
 *     <li>耐火 + 炎の弱点: {@link LivingIncomingDamageEvent} で、炎系のダメージ
 *     ({@link DamageTypeTags#IS_FIRE})をグループ1〜10 なら {@code fireWeaknessMultiplier} 倍、
 *     さらに耐火個体なら {@code fireproof.fireDamageMultiplier} 倍にする。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class MobVariants {

    /** 硬化個体の防御値修飾の id(永続。保存された個体は読み込み時にも属性ごと復元される)。 */
    public static final ResourceLocation HARDENED_ARMOR = OvergrownVegetation.id("variant_hardened_armor");

    /** その個体の種別。未抽選・対象外なら {@code null}。サーバー/クライアント両方で使える。 */
    @Nullable
    public static MobVariant get(Entity entity) {
        Integer code = entity.getExistingData(ModAttachments.MOB_VARIANT).orElse(null);
        return code != null ? MobVariant.byCode(code) : null;
    }

    /** {@link #get} が {@code null} でも通常個体として扱う版。 */
    public static MobVariant getOrNormal(Entity entity) {
        MobVariant v = get(entity);
        return v != null ? v : MobVariant.NORMAL;
    }

    public static boolean is(Entity entity, MobVariant variant) {
        return get(entity) == variant;
    }

    /** クライアント側で同期パケットから種別を書き込む。 */
    public static void setClient(Entity entity, int code) {
        entity.setData(ModAttachments.MOB_VARIANT, code);
    }

    // --- 抽選 ---

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        if (mob.getExistingData(ModAttachments.MOB_VARIANT).isPresent()) {
            return; // 保存から戻ってきた個体、または既に抽選済み
        }
        if (!isEligible(mob) || !isUnlocked(level)) {
            return;
        }
        MobVariant variant = roll(mob.getRandom(), DesignData.variants());
        mob.setData(ModAttachments.MOB_VARIANT, variant.code());
        applyPassive(mob, variant);
    }

    /** 解禁を調べ直す間隔(tick)。湧くたびに保存データを引きに行くと無駄が多い。 */
    private static final int GATE_RECHECK_TICKS = 100;
    private static long gateCheckedAt = Long.MIN_VALUE;
    private static boolean gateOpen;

    /**
     * 亜種が解禁されているか。解禁は<b>世界全体</b>の旗({@link VariantUnlockData})で、
     * 閉じているあいだは全部通常個体(抽選しない)。2026-09-24 までは「アルボラの巣の孵化室が
     * 1 室でもあれば解禁」だったが、巣を消したので旗にした。開けるのは将来の器官の予定。
     * 孵化室があった古いワールドは旗を作るときに開いた状態で引き継ぐ({@link VariantUnlockData#get})。
     */
    public static boolean isUnlocked(ServerLevel level) {
        long now = level.getGameTime();
        if (gateCheckedAt == Long.MIN_VALUE || now - gateCheckedAt >= GATE_RECHECK_TICKS || now < gateCheckedAt) {
            gateCheckedAt = now;
            gateOpen = VariantUnlockData.isUnlocked(level);
        }
        return gateOpen;
    }

    /** {@link #isUnlocked} の控えを捨てる(旗を書き換えたとき、{@link VariantUnlockData#setUnlocked} から)。 */
    static void forgetUnlockCache() {
        gateCheckedAt = Long.MIN_VALUE;
    }

    /** 抽選の対象になる Mob か(グループ・除外リスト・動けるか)。 */
    public static boolean isEligible(Mob mob) {
        MobGroup group = MobGroup.of(mob.getType());
        VariantTable table = DesignData.variants();
        if (group == null || !table.eligibleGroups().contains(group.number())) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
        for (String raw : table.excluded()) {
            if (matches(id, raw)) {
                return false;
            }
        }
        AttributeInstance speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
        return speed != null && speed.getValue() > 0.0D;
    }

    private static boolean matches(ResourceLocation id, String raw) {
        if (raw.contains(":")) {
            return id.toString().equals(raw);
        }
        return id.getNamespace().equals(OvergrownVegetation.MODID) && id.getPath().equals(raw);
    }

    /** 重みで種別を 1 つ引く。重みが全部 0 なら通常個体。 */
    public static MobVariant roll(RandomSource random, VariantTable table) {
        double normal = table.normalWeight();
        double hardened = table.hardenedWeight();
        double toxic = table.toxicWeight();
        double fireproof = table.fireproofWeight();
        double total = normal + hardened + toxic + fireproof;
        if (total <= 0.0D) {
            return MobVariant.NORMAL;
        }
        double r = random.nextDouble() * total;
        if ((r -= normal) < 0.0D) {
            return MobVariant.NORMAL;
        }
        if ((r -= hardened) < 0.0D) {
            return MobVariant.HARDENED;
        }
        if ((r -= toxic) < 0.0D) {
            return MobVariant.TOXIC;
        }
        return MobVariant.FIREPROOF;
    }

    /** 抽選時に 1 回だけ付ける受動効果(硬化の防御値)。 */
    private static void applyPassive(Mob mob, MobVariant variant) {
        if (variant != MobVariant.HARDENED) {
            return;
        }
        AttributeInstance armor = mob.getAttribute(Attributes.ARMOR);
        if (armor == null || armor.hasModifier(HARDENED_ARMOR)) {
            return;
        }
        double multiplier = DesignData.variants().hardenedArmorMultiplier();
        // 防御値 0 の種(家畜ベースの媒介種など)は 1.3 倍しても 0 のまま — 仕様どおり「防御値の倍率」で揃える。
        armor.addPermanentModifier(new AttributeModifier(HARDENED_ARMOR, multiplier - 1.0D,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    // --- 同期 ---

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Entity target = event.getTarget();
        Integer code = target.getExistingData(ModAttachments.MOB_VARIANT).orElse(null);
        // 2026-09-24 軽量化(監査 2121): 通常個体(code 1)は送らない。クライアントで種別を読むのは
        // テクスチャの解決(client/MobVariantTextures)だけで、値が無いときも通常のときも接尾辞なしの
        // png を返すので見た目は同じ。種別は湧いたときに 1 回決まるだけで、追跡中に変わる経路は無い。
        if (code != null && code > MobVariant.NORMAL.code()) {
            PacketDistributor.sendToPlayer(player, new MobVariantPayload(target.getId(), code));
        }
    }

    // --- 効果 ---

    /** 炎の弱点(グループ1〜10)と耐火個体。 */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide() || !event.getSource().is(DamageTypeTags.IS_FIRE)) {
            return;
        }
        if (MobGroup.of(victim.getType()) == null) { // 寄生植物装備のプレイヤー(グループ6扱い)は対象外
            return;
        }
        VariantTable table = DesignData.variants();
        double multiplier = table.fireWeaknessMultiplier();
        if (is(victim, MobVariant.FIREPROOF)) {
            multiplier *= table.fireproofFireMultiplier();
        }
        if (multiplier != 1.0D) {
            event.setAmount((float) (event.getAmount() * multiplier));
        }
    }

    /** 毒性個体の攻撃に毒を乗せる。 */
    @SubscribeEvent
    public static void onDamagePost(LivingDamageEvent.Post event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide() || !victim.isAlive()) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof LivingEntity attacker) || attacker == victim) {
            return;
        }
        if (!is(attacker, MobVariant.TOXIC)) {
            return;
        }
        int ticks = DesignData.variants().toxicPoisonTicks();
        if (ticks <= 0) {
            return;
        }
        victim.addEffect(new MobEffectInstance(MobEffects.POISON, ticks, 0), attacker);
    }

    /** 亜種のテクスチャ名(接尾辞付き)。通常個体・未抽選なら {@code base} のまま。 */
    public static String textureName(Entity entity, String base) {
        MobVariant v = get(entity);
        return v == null || v == MobVariant.NORMAL ? base : base + "_" + v.suffix();
    }

    /** デバッグ・表示用: 種別の翻訳キー。 */
    public static String translationKey(MobVariant variant) {
        return "variant." + OvergrownVegetation.MODID + "." + variant.suffix();
    }

    private MobVariants() {
    }
}
