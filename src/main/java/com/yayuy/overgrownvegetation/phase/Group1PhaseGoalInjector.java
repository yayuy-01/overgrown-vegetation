package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.world.entity.PathfinderMob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * レベルに参加するグループ1({@link MobGroup#GROUP_1})の {@link PathfinderMob} に
 * {@link Group1FleeCorruptionGoal} を優先度1で仕込む。Goal 自体はフェーズ2未満・設定 OFF の間は
 * 何もしないので、常時追加しておいても普段の挙動には影響しない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class Group1PhaseGoalInjector {

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof PathfinderMob mob && MobGroup.of(mob) == MobGroup.GROUP_1) {
            mob.goalSelector.addGoal(1, new Group1FleeCorruptionGoal(mob));
        }
    }

    private Group1PhaseGoalInjector() {
    }
}
