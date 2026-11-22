package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.PhaseTable;
import com.yayuy.overgrownvegetation.network.PhaseStatePayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.player.PlayerEvent;
import com.yayuy.overgrownvegetation.port.network.PacketDistributor;

/**
 * 今のフェーズと小さな段をクライアントへ送る(2026-11-14)。送るのは、フェーズ・段が変わったとき({@link PhaseData} から)・
 * 進みが 1% 動いたとき({@link PhaseTicker} から 5 秒おきに見る)・ログインしたとき。毎 tick は送らない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class PhaseSync {

    /** 最後に送った「フェーズ・段・進みの %」(変わったときだけ送る)。サーバーの起動ごとに最初の 1 回は必ず送る。 */
    private static long lastSentKey = Long.MIN_VALUE;

    private PhaseSync() {
    }

    static PhaseStatePayload payload(PhaseData data) {
        PhaseTable table = DesignData.phases();
        int phase = data.getPhase();
        double progress = phase < 1 ? -1.0D : table.stageProgress(phase, data.getPoints());
        double strength = PhaseStages.strength(table, phase, data.getStage(), phase);
        return new PhaseStatePayload(phase, data.getStage().ordinal(), (float) progress, (float) strength);
    }

    private static long key(PhaseStatePayload p) {
        int percent = p.progress() < 0.0F ? -1 : (int) Math.floor(p.progress() * 100.0F);
        return ((long) p.phase() << 32) | ((long) p.stage() << 16) | (percent & 0xFFFFL);
    }

    public static void sendToAll(MinecraftServer server, PhaseData data) {
        PhaseStatePayload p = payload(data);
        lastSentKey = key(p);
        PacketDistributor.sendToAllPlayers(p);
    }

    /** 5 秒おき。進みの % が動いていたら送る。 */
    static void tickProgress(MinecraftServer server, PhaseData data) {
        PhaseStatePayload p = payload(data);
        long k = key(p);
        if (k != lastSentKey) {
            lastSentKey = k;
            PacketDistributor.sendToAllPlayers(p);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PacketDistributor.sendToPlayer(player, payload(PhaseData.get(player.serverLevel())));
        }
    }
}
