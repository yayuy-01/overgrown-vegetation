package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * 1.20.1 だけ(G8。2026-11-23): GameTest のサーバーを 1.21 のバニラのテストサーバーと同じ決まりにする。
 *
 * <p>Forge 1.20.1 の {@code runGameTestServer} は {@code GameTestMain} → 普通の {@code Main} で、ワールドは server.properties のとおり作り、
 * ゲームのルールも既定のまま({@code GameTestServer.create} の TEST_GAME_RULES・平らな世界・難しさ NORMAL を通らない)。
 * 1.21 のテストサーバーは Mob が湧かず・天気が巡らず・難しさ NORMAL・平らな世界。ここではルールと難しさを合わせる。
 * 平らな世界は server.properties の {@code level-type=minecraft:flat}(ワールドを作る前に決まるので、流す側の init スクリプトが書く)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class GtServerSetup {

    @SubscribeEvent
    public static void onGtServerStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean("forge.gameTestServer")) {
            return;
        }
        MinecraftServer server = event.getServer();
        GameRules rules = server.getGameRules();
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        // 2026-11-23 統合 I: 1.21 の TEST_GAME_RULES は、ほかにランダムティック 0・火の広がり無し(1.20.1 の GameTestServer には無い)。
        // 無いと葉が枯れる・覆いや植物がテストの途中で勝手に広がり、器官の払い・覆いの広がりなどのテストが回ごとに落ちた
        rules.getRule(GameRules.RULE_RANDOMTICKING).set(0, server);
        rules.getRule(GameRules.RULE_DOFIRETICK).set(false, server);
        server.setDifficulty(Difficulty.NORMAL, true);
        OvergrownVegetation.LOGGER.info("[gametest] 1.21 のテストサーバーと同じ決まりにした(Mob が湧かない・天気が巡らない・ランダムティック 0・火が広がらない・難しさ NORMAL)。世界の型 = {}",
                server.overworld().getChunkSource().getGenerator().getClass().getSimpleName());
    }

    private GtServerSetup() {
    }
}
