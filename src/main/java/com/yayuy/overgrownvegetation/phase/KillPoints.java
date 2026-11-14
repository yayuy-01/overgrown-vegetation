package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * フェーズの撃破の点(2026-11-14 ユーザー決定「ふつうの 1 日で撃破 10 点」。中身は【仮・Claude】)。
 *
 * <p>プレイヤー(偽のプレイヤーは除く)が倒した<b>植物側の Mob だけ</b>を数える。点は {@code phases.json} の {@code global.points.kills}:
 * G1 の 1 段目(発達した寄生体 {@code developed_*})= evolved、それ以外の G1(段階 0・根付き・苗床)= stage0、G4・G5・G6。
 * G2・G3(環境の生き物)と G7 以降は 0。1 日の上限は {@link PhaseData#addKillPoints}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class KillPoints {

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (!(dead.level() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof Player player) || player instanceof FakePlayer) {
            return;
        }
        int kind = kindOf(dead.getType());
        if (kind < 0) {
            return;
        }
        int amount = DesignData.phases().killPoints(kind);
        if (amount > 0) {
            ServerLevel overworld = level.getServer().overworld();
            PhaseData.get(overworld).addKillPoints(overworld, amount);
        }
    }

    /** 撃破の点の種類: 0 = 段階 0・1 = 進化体・2 = G4・3 = G5・4 = G6。数えない Mob は -1。 */
    public static int kindOf(EntityType<?> type) {
        MobGroup group = MobGroup.of(type);
        if (group == null) {
            return -1;
        }
        return switch (group.number()) {
            case 1 -> BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath().startsWith("developed_") ? 1 : 0;
            case 4 -> 2;
            case 5 -> 3;
            case 6 -> 4;
            default -> -1;
        };
    }

    private KillPoints() {
    }
}
