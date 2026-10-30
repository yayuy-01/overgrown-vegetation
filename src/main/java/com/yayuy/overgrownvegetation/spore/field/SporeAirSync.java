package com.yayuy.overgrownvegetation.spore.field;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.SporeAirPayload;
import com.yayuy.overgrownvegetation.spore.SporeConcentration;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * プレイヤーの所の胞子濃度を 5 秒ごと(場の更新と同じ間隔)に送る(2026-10-30、密林の雰囲気の提案 2。{@link SporeAirPayload})。
 * 値は目の高さの {@link SporeConcentration#percentAt}(場のキャッシュを読むだけ)。場が止まっている次元では 0 を送る。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID)
public final class SporeAirSync {

    private static final int INTERVAL = 100;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % INTERVAL != 0) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            float percent = (float) SporeConcentration.percentAt(player.serverLevel(), player.blockPosition().above());
            PacketDistributor.sendToPlayer(player, new SporeAirPayload(percent));
        }
    }

    private SporeAirSync() {
    }
}
