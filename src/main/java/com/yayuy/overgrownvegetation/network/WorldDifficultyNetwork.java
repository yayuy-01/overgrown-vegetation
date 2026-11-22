package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.difficulty.WorldDifficultySync;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.network.RegisterPayloadHandlersEvent;
import com.yayuy.overgrownvegetation.port.network.PayloadRegistrar;

/**
 * MOD の設定ページの通信(2026-11-19)。共有の {@code ModNetwork} とは別の登録にして、版を自分で持つ({@code ResearchNetwork} と同じ作法)。
 * 版 1: 問い合わせ {@link WorldDifficultyRequestPayload}(C → S)・返事 {@link WorldDifficultySyncPayload}(S → C)・
 * 「完了」{@link WorldDifficultyApplyPayload}(C → S)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class WorldDifficultyNetwork {

    private WorldDifficultyNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("settings-1");
        registrar.playToServer(
                WorldDifficultyRequestPayload.TYPE,
                WorldDifficultyRequestPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) WorldDifficultySync.sendTo(player);
                }));
        registrar.playToServer(
                WorldDifficultyApplyPayload.TYPE,
                WorldDifficultyApplyPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) WorldDifficultySync.apply(player, payload.values());
                }));
        registrar.playToClient(
                WorldDifficultySyncPayload.TYPE,
                WorldDifficultySyncPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.difficulty.ClientWorldDifficulty.accept(payload)));
    }
}
