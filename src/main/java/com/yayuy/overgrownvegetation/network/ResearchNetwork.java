package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.research.Research;
import com.yayuy.overgrownvegetation.research.ResearchBenchBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 研究台と研究ツリーの通信(2026-11-17)。共有の {@code ModNetwork} とは別の登録にして、版を自分で持つ({@code SurvivalNetwork} と同じ作法)。
 * 版 1: 状態 {@link ResearchStatePayload}(S → C)・操作 {@link ResearchActionPayload}(C → S)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ResearchNetwork {

    private static final double REACH_SQ = 8 * 8;

    private ResearchNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("research-1");
        registrar.playToClient(
                ResearchStatePayload.TYPE,
                ResearchStatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.research.ResearchScreen.accept(payload)));
        registrar.playToServer(
                ResearchActionPayload.TYPE,
                ResearchActionPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (!(context.player() instanceof ServerPlayer player)) return;
                    if (payload.action() == ResearchActionPayload.CLOSE) {
                        Research.closeScreen(player);
                        return;
                    }
                    if (player.distanceToSqr(payload.bench().getCenter()) > REACH_SQ
                            || !(player.level().getBlockEntity(payload.bench()) instanceof ResearchBenchBlockEntity)) {
                        return;
                    }
                    if (payload.action() == ResearchActionPayload.START) {
                        Component why = Research.start(player, payload.bench(), payload.node());
                        if (why != null) player.displayClientMessage(why, true);
                    } else if (payload.action() == ResearchActionPayload.CANCEL) {
                        if (Research.cancel(player.server, player)) {
                            player.displayClientMessage(Component.translatable("research.overgrown_vegetation.cancelled"), true);
                        }
                    }
                }));
    }
}
