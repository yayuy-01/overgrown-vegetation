package com.yayuy.overgrownvegetation.difficulty;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.ExperimentalPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.player.PlayerEvent;
import com.yayuy.overgrownvegetation.port.network.PacketDistributor;
import com.yayuy.overgrownvegetation.port.network.RegisterPayloadHandlersEvent;

/**
 * 実験的要素の値をクライアントへ送る(2026-11-23 夜。共有の {@code ModNetwork} とは別の登録で、版を自分で持つ)。
 * 送るのはログインしたときと、{@link ExperimentalFeatures#set}・設定の画面の「完了」で値が変わったとき。
 */
public final class ExperimentalSync {

    private ExperimentalSync() {
    }

    public static void sendAll(MinecraftServer server) {
        if (server.getPlayerList().getPlayerCount() == 0) return;
        PacketDistributor.sendToAllPlayers(new ExperimentalPayload(ExperimentalFeatures.mask(server)));
    }

    static void sendTo(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new ExperimentalPayload(ExperimentalFeatures.mask(player.server)));
    }

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Network {
        private Network() {
        }

        @SubscribeEvent
        public static void register(RegisterPayloadHandlersEvent event) {
            event.registrar("experimental-1").playToClient(
                    ExperimentalPayload.TYPE,
                    ExperimentalPayload.STREAM_CODEC,
                    (payload, context) -> context.enqueueWork(() -> {
                        if (ExperimentalFeatures.acceptClient(payload.mask())) {
                            com.yayuy.overgrownvegetation.client.difficulty.ClientExperimental.onChanged(); // タブを作り直させる
                        }
                    }));
        }
    }

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
    public static final class Login {
        private Login() {
        }

        @SubscribeEvent
        public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof ServerPlayer sp) sendTo(sp);
        }
    }
}
