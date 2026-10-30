package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * 覆いの記録をクライアントへ送る口(チャンクを見始めたら全部・tick の終わりに差分。2026-09-22)。
 *
 * <p>2026-10-30 まではここで覆われたマスの右クリックを全部止めていた。覆いの作り直しで、扉・ボタンなどが時々動かない作りと一緒に
 * {@link CoveredInteractions} へ移した(剥がすのは {@link CoverPeel})。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class OvergrownLayerInteraction {

    private OvergrownLayerInteraction() {
    }

    /** チャンクを見始めたプレイヤーへ、そのチャンクの記録をまるごと送る。 */
    @SubscribeEvent
    public static void onChunkWatch(ChunkWatchEvent.Sent event) {
        ServerPlayer player = event.getPlayer();
        LevelChunk chunk = event.getChunk();
        OvergrownLayerData.sendAll(player, chunk);
    }

    /** この tick で新しく覆われたぶんを、まとめて送る。 */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            OvergrownLayerData.flush(level);
        }
    }
}
