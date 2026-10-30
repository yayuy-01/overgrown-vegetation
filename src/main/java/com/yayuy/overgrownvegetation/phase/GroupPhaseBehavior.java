package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.PhaseTable;
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
 * フェーズごとに、グループ単位で Mob の体力・攻撃力を強化する
 * ({@code phases.json} の {@code groups} — グループ番号 → 倍率)。
 *
 * <p>2026-09-25 まではグループ1 専用({@code Group1PhaseBehavior} / {@code phases.json} の {@code group1})だったが、
 * ユーザー指定で {@link PhaseTable#BUFFABLE_GROUPS}(1・4〜9)まで広げた。<b>既定で強化されるのは今も
 * グループ1 だけ</b>(フェーズ2以降、体力2倍・攻撃力1.5倍)—— 他のグループは倍率を書くまで素のまま。
 *
 * <p>強化は透過的な {@link AttributeModifier} なので、フェーズが戻れば(コマンドなど)自動的に外れる。
 * 初めて体力補正が付いた瞬間だけ最大 HP まで回復させる。Mob は 1 つのグループにしか属さないので、
 * 補正の id はグループ間で使い回している。
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
 * {@code SporeInfectionDeathHandler}(感染死で死体/シダを残す)+
 * {@link Group1FleeCorruptionGoal}(汚染地形から離れる = {@code groups."1".fleeCorruption})の
 * 組み合わせで実現している。こちらは体力・攻撃力だけを見る。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class GroupPhaseBehavior {

    // id は 2026-09-25 のグループ拡張より前から付いているので、名前はそのまま(付け替えると
    // 既にワールドに居る個体の古い補正が外れずに二重掛かりになる)。
    private static final ResourceLocation HEALTH_MODIFIER_ID = OvergrownVegetation.id("phase2_group1_health");
    private static final ResourceLocation ATTACK_MODIFIER_ID = OvergrownVegetation.id("phase2_group1_attack");

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof LivingEntity living && buffable(living)) {
            // 参加時は回復させない(再ログインで HP が全快するのを防ぐ)。
            refresh(living, false);
        }
    }

    /** フェーズが変化したとき、全次元のロード済みの該当 Mob へ強化を反映/解除する。 */
    public static void onPhaseChanged(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof LivingEntity living && buffable(living)) {
                    // 強化が付いた瞬間だけ、最大 HP が増えたぶんを埋めるために全快させる。
                    refresh(living, true);
                }
            }
        }
    }

    /** 強化の対象になりうるグループの Mob か(グループ2・3・10 と、グループ外は対象外)。 */
    private static boolean buffable(LivingEntity mob) {
        MobGroup group = MobGroup.of(mob);
        return group != null && PhaseTable.BUFFABLE_GROUPS.contains(group.number());
    }

    private static void refresh(LivingEntity mob, boolean healOnNewBuff) {
        if (!(mob.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        MobGroup group = MobGroup.of(mob);
        if (group == null) {
            return;
        }
        int phase = PhaseData.get(serverLevel).getPhase();
        PhaseTable phases = DesignData.phases();
        boolean buffed = phases.groupBuffed(phase, group.number());
        double healthAmount = buffed ? phases.groupHealthMultiplier(phase, group.number()) - 1.0D : 0.0D;
        double attackAmount = buffed ? phases.groupAttackMultiplier(phase, group.number()) - 1.0D : 0.0D;
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

    private GroupPhaseBehavior() {
    }
}
