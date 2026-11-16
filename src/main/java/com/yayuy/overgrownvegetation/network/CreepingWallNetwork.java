package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 蠢く壁の通信(2026-11-16 試作)。共有の {@code ModNetwork} とは別の登録にして、版を自分で持つ。
 * 版 1: 先頭の升 {@link CreepingWallPayload}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class CreepingWallNetwork {

    private CreepingWallNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        // 版 2: 前触れ(CreepingWallPayload.OMEN)と、殴った知らせ CreepingWallHitPayload(C2S)を足した(11-16)
        PayloadRegistrar registrar = event.registrar("creeping-wall-2");
        registrar.playToClient(
                CreepingWallPayload.TYPE,
                CreepingWallPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.wall.ClientCreepingWall.accept(payload)));
        registrar.playToServer(
                CreepingWallHitPayload.TYPE,
                CreepingWallHitPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                        com.yayuy.overgrownvegetation.wall.CreepingWalls.onHit(player, payload.cell());
                    }
                }));
    }
}
