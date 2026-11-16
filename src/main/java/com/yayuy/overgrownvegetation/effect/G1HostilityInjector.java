package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * 「G1〜G10 は、すべての Mob から平等に敵対される」(11-05 ユーザー、問い 12 への答え。鉄のゴーレムなども、元の Mob が何であっても G1 を狙う)。
 *
 * <p>攻撃の力を持つ Mob(属性 ATTACK_DAMAGE がある {@link PathfinderMob}: ゾンビ・スケルトン・クモ・狼・鉄のゴーレム・ホグリンなど)に、
 * G1〜G10(グループのタグ)を狙う標的の Goal を 1 つ足す。牛・羊・村人のように攻撃を持たない Mob には足さない(足しても殴れない)。
 * 寄生体どうしは足さない(G1 はもともと G1〜G10 を襲わない)。視線は要る(バニラの敵対 Mob と同じ見つけ方)。
 * 旧 {@code effect/ParasitizedHostilityInjector}(parked。バニラの敵対 Mob だけに足していた)を広げて書き直した。
 *
 * <p>鉄のゴーレム・スノーゴーレムはこれが無くても G1 を狙う(G1 は Monster = {@code Enemy})。弓を持つスケルトンは自分の攻撃の Goal で撃つ。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class G1HostilityInjector {

    /** 足す Goal の優先度(バニラの標的の Goal は 1〜3。同じ優先度に複数あってよい)。 */
    static final int PRIORITY = 3;

    private G1HostilityInjector() {
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof PathfinderMob mob)) {
            return;
        }
        if (mob instanceof ParasitizedMob || com.yayuy.overgrownvegetation.spore.PlantSideRules.isPlantSide(mob)
                || !mob.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE)) {
            return;
        }
        if (hasInjected(mob)) {
            return; // 区画の読み直しで入り直したとき
        }
        mob.targetSelector.addGoal(PRIORITY, new HuntG1(mob));
    }

    private static boolean isOvergrown(LivingEntity e) {
        return com.yayuy.overgrownvegetation.spore.PlantSideRules.isPlantSide(e); // 陣営(11-16 ③6 でそろえた)
    }

    /** この Mob に足したか(区画の読み直しで二重に足さない・GameTest)。 */
    public static boolean hasInjected(Mob mob) {
        return mob.targetSelector.getAvailableGoals().stream().anyMatch(g -> g.getGoal() instanceof HuntG1);
    }

    /** G1〜G10 を狙う標的の Goal(印のための型)。 */
    static final class HuntG1 extends NearestAttackableTargetGoal<LivingEntity> {
        HuntG1(Mob mob) {
            super(mob, LivingEntity.class, 10, true, false, G1HostilityInjector::isOvergrown);
        }
    }
}
