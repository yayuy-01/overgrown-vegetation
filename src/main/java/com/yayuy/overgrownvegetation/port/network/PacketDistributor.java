package com.yayuy.overgrownvegetation.port.network;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

/**
 * 【1.20.1 だけの物・互換の層】NeoForge 1.21 の {@code net.neoforged.neoforge.network.PacketDistributor}(static の送り方)と同じ名前・同じ引数。
 * 中身は Forge の SimpleChannel({@link OvChannel#channel()})と Forge の {@code PacketDistributor}。
 */
public final class PacketDistributor {

    private PacketDistributor() {
    }

    private static final net.minecraftforge.network.PacketDistributor<ServerPlayer> F_PLAYER = net.minecraftforge.network.PacketDistributor.PLAYER;

    public static void sendToServer(CustomPacketPayload payload, CustomPacketPayload... more) {
        OvChannel.sendToServer(payload);
        for (CustomPacketPayload p : more) {
            OvChannel.sendToServer(p);
        }
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload, CustomPacketPayload... more) {
        send(F_PLAYER.with(() -> player), payload, more);
    }

    public static void sendToPlayersInDimension(ServerLevel level, CustomPacketPayload payload, CustomPacketPayload... more) {
        send(net.minecraftforge.network.PacketDistributor.DIMENSION.with(level::dimension), payload, more);
    }

    public static void sendToPlayersNear(ServerLevel level, @Nullable ServerPlayer excluded, double x, double y, double z, double radius,
                                         CustomPacketPayload payload, CustomPacketPayload... more) {
        var point = new net.minecraftforge.network.PacketDistributor.TargetPoint(excluded, x, y, z, radius * radius, level.dimension());
        send(net.minecraftforge.network.PacketDistributor.NEAR.with(() -> point), payload, more);
    }

    public static void sendToAllPlayers(CustomPacketPayload payload, CustomPacketPayload... more) {
        send(net.minecraftforge.network.PacketDistributor.ALL.noArg(), payload, more);
    }

    public static void sendToPlayersTrackingEntity(Entity entity, CustomPacketPayload payload, CustomPacketPayload... more) {
        send(net.minecraftforge.network.PacketDistributor.TRACKING_ENTITY.with(() -> entity), payload, more);
    }

    public static void sendToPlayersTrackingEntityAndSelf(Entity entity, CustomPacketPayload payload, CustomPacketPayload... more) {
        send(net.minecraftforge.network.PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> entity), payload, more);
    }

    /** チャンクを読み込まずに、そのチャンクを見ているプレイヤーへ送る(NeoForge と同じ)。 */
    public static void sendToPlayersTrackingChunk(ServerLevel level, ChunkPos chunk, CustomPacketPayload payload, CustomPacketPayload... more) {
        for (ServerPlayer p : level.getChunkSource().chunkMap.getPlayers(chunk, false)) {
            sendToPlayer(p, payload, more);
        }
    }

    private static void send(net.minecraftforge.network.PacketDistributor.PacketTarget target, CustomPacketPayload payload, CustomPacketPayload... more) {
        OvChannel.channel().send(target, OvChannel.wrap(payload));
        for (CustomPacketPayload p : more) {
            OvChannel.channel().send(target, OvChannel.wrap(p));
        }
    }
}
