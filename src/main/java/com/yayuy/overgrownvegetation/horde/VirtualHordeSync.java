package com.yayuy.overgrownvegetation.horde;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.VirtualHordePayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.event.tick.ServerTickEvent;
import com.yayuy.overgrownvegetation.port.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 仮想の群れをクライアントへ送る(2026-11-18 大群の軽量化 A)。{@value #INTERVAL} tick ごとに、プレイヤーから水平 {@value #RADIUS} ブロック以内の群れを
 * 近い順に {@link VirtualHordePayload#MAX_SWARMS} まで、差分なしで送る。前に送った相手で 0 になったら空を 1 回送る。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class VirtualHordeSync {

    static final int INTERVAL = 20;
    static final double RADIUS = 512.0D;

    /** 前の回に空でない一覧を送ったプレイヤー。 */
    private static final Set<UUID> SENT = new HashSet<>();

    private VirtualHordeSync() {
    }

    /** 抜けた人・止めたサーバーの印を捨てる(11-18 見直し ③7)。 */
    @SubscribeEvent
    public static void onLogout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        SENT.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(net.minecraftforge.event.server.ServerStoppedEvent event) {
        SENT.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % INTERVAL != 0 || !VirtualHordes.enabled()) return;
        List<VirtualHordes.Swarm> all = VirtualHordes.get(server).swarms();
        for (ServerPlayer p : server.overworld().players()) {
            List<VirtualHordes.Swarm> near = new ArrayList<>();
            for (VirtualHordes.Swarm s : all) {
                double dx = s.x() - p.getX(), dz = s.z() - p.getZ();
                if (dx * dx + dz * dz <= RADIUS * RADIUS) near.add(s);
            }
            if (near.isEmpty()) {
                if (SENT.remove(p.getUUID())) PacketDistributor.sendToPlayer(p, new VirtualHordePayload(List.of()));
                continue;
            }
            near.sort(Comparator.comparingDouble(s -> (s.x() - p.getX()) * (s.x() - p.getX()) + (s.z() - p.getZ()) * (s.z() - p.getZ())));
            List<VirtualHordePayload.Swarm> out = new ArrayList<>(Math.min(near.size(), VirtualHordePayload.MAX_SWARMS));
            for (VirtualHordes.Swarm s : near) {
                if (out.size() >= VirtualHordePayload.MAX_SWARMS) break;
                List<VirtualHordePayload.Count> counts = new ArrayList<>();
                for (Map.Entry<ResourceLocation, Integer> e : s.counts().entrySet()) {
                    counts.add(new VirtualHordePayload.Count(BuiltInRegistries.ENTITY_TYPE.get(e.getKey()), e.getValue()));
                }
                out.add(new VirtualHordePayload.Swarm(s.id(), s.x(), s.y(), s.z(), s.dirX(), s.dirZ(), (float) s.speed(), s.firstIndex(), counts));
            }
            PacketDistributor.sendToPlayer(p, new VirtualHordePayload(out));
            SENT.add(p.getUUID());
        }
    }
}
