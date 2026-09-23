package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.world.entity.PathfinderMob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * レベルに参加する全ての {@link PathfinderMob}(プレイヤー・地上を歩かない Mob を除く、
 * 一般的な Mob 全般)に {@link FleeCorruptionGoal} を仕込む。Goal 自体は「胞子感染」に
 * かかっていて、かつ汚染ブロックの上にいる間しか動作しないので、常時追加しておいても
 * 普段の挙動には影響しない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SporeInfectionGoalInjector {

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof PathfinderMob mob) {
            mob.goalSelector.addGoal(1, new FleeCorruptionGoal(mob));
        }
    }

    private SporeInfectionGoalInjector() {
    }
}
