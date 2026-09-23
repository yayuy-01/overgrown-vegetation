package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * フェーズ2以降、グループ1({@link MobGroup#GROUP_1})の Mob の体力・攻撃力を強化する。
 *
 * <p>強化は透過的な {@link AttributeModifier}(既定: 体力 +100% / 攻撃力 +50%、config で調整可)で、
 * フェーズが2未満に戻れば(コマンドなど)自動的に外れる。初めて体力補正が付いた瞬間だけ最大 HP まで
 * 回復させる。
 *
 * <p>反映タイミングは 2 つ:
 * <ul>
 *     <li>該当 Mob がレベルに参加したとき({@link EntityJoinLevelEvent})。</li>
 *     <li>フェーズが変化したとき({@link PhaseData} から {@link #onPhaseChanged} を呼ぶ)—
 *     ロード済みの該当 Mob 全てに即座に反映/解除する。</li>
 * </ul>
 *
 * <p>グループ1が「汚染地形から離れて他の Mob を襲い、倒した相手を汚染死体/汚染シダに変えて
 * 汚染を広げる」動き自体は、既存の {@code ParasitizedTargetGoal}(全方位に敵対)+
 * {@code ParasitizedAttackInfectionHandler}(攻撃で胞子感染を付与)+
 * {@code SporeInfectionDeathHandler}(感染死で死体/シダを残す)+ フェーズ2で効く
 * {@link Group1FleeCorruptionGoal}(汚染地形から離れる)の組み合わせで実現している。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class Group1PhaseBehavior {

    private static final ResourceLocation HEALTH_MODIFIER_ID = OvergrownVegetation.id("phase2_group1_health");
    private static final ResourceLocation ATTACK_MODIFIER_ID = OvergrownVegetation.id("phase2_group1_attack");

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof LivingEntity living && MobGroup.of(living) == MobGroup.GROUP_1) {
            // 参加時は回復させない(再ログインで HP が全快するのを防ぐ)。
            refresh(living, false);
        }
    }

    /** フェーズが変化したとき、全次元のロード済みグループ1 Mob へ強化を反映/解除する。 */
    public static void onPhaseChanged(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof LivingEntity living && MobGroup.of(living) == MobGroup.GROUP_1) {
                    // フェーズ2へ上がった瞬間だけ、最大 HP が増えたぶんを埋めるために全快させる。
                    refresh(living, true);
                }
            }
        }
    }

    private static void refresh(LivingEntity mob, boolean healOnNewBuff) {
        if (!(mob.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        int phase = PhaseData.get(serverLevel).getPhase();
        com.yayuy.overgrownvegetation.data.PhaseTable phases = com.yayuy.overgrownvegetation.data.DesignData.phases();
        boolean buffed = phases.group1Buffed(phase);
        double healthAmount = buffed ? phases.group1HealthMultiplier(phase) - 1.0D : 0.0D;
        double attackAmount = buffed ? phases.group1AttackMultiplier(phase) - 1.0D : 0.0D;
        applyModifier(mob, Attributes.MAX_HEALTH, HEALTH_MODIFIER_ID, healthAmount, healOnNewBuff);
        applyModifier(mob, Attributes.ATTACK_DAMAGE, ATTACK_MODIFIER_ID, attackAmount, false);
    }

    /**
     * {@code attribute} に id 固定の {@code ADD_MULTIPLIED_TOTAL} 補正を付ける/更新する/外す。
     *
     * @param amount    0 以下なら補正を外す。それ以外は「最終値 = 素の合計 ×(1 + amount)」。
     * @param healToFull 補正が新規に付いたときに最大 HP まで回復させるか(体力補正のみ true)。
     */
    private static void applyModifier(LivingEntity mob, Holder<Attribute> attribute, ResourceLocation id,
                                      double amount, boolean healToFull) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        AttributeModifier existing = instance.getModifier(id);
        if (amount <= 0.0D) {
            if (existing != null) {
                instance.removeModifier(id);
            }
            return;
        }
        if (existing != null) {
            if (existing.amount() == amount) {
                return;
            }
            instance.removeModifier(id);
        }
        instance.addOrUpdateTransientModifier(
                new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        if (healToFull && existing == null) {
            mob.setHealth(mob.getMaxHealth());
        }
    }

    private Group1PhaseBehavior() {
    }
}
