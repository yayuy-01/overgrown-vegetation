package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.level.ChunkWatchEvent;
import com.yayuy.overgrownvegetation.port.event.tick.LevelTickEvent;

/**
 * 覆いの記録をクライアントへ送る口(チャンクを見始めたら全部・tick の終わりに差分。2026-09-22)。
 *
 * <p>2026-10-30 まではここで覆われたマスの右クリックを全部止めていた。覆いの作り直しで、扉・ボタンなどが時々動かない作りと一緒に
 * {@link CoveredInteractions} へ移した(剥がすのは {@link CoverPeel})。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class OvergrownLayerInteraction {

    private OvergrownLayerInteraction() {
    }

    /** チャンクを見始めたプレイヤーへ、そのチャンクの記録をまるごと送る。 */
    @SubscribeEvent
    // 1.20.1: Sent が無い。Watch はチャンクの包みを送った直後に来る
    public static void onChunkWatch(ChunkWatchEvent.Watch event) {
        ServerPlayer player = event.getPlayer();
        LevelChunk chunk = event.getChunk();
        OvergrownLayerData.sendAll(player, chunk);
        CoverCreepData.sendAll(player, chunk); // 這い上がる苔の記録(2026-11-01)
    }

    /** この tick で新しく覆われたぶんを、まとめて送る。 */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            OvergrownLayerData.flush(level);
            CoverCreepData.flush(level);
        }
    }
}
