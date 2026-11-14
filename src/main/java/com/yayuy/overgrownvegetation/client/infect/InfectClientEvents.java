package com.yayuy.overgrownvegetation.client.infect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/** 汎用の感染体の、クライアント側の後片付けと確かめ用の入口。 */
public final class InfectClientEvents {

    private InfectClientEvents() {
    }

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        @SubscribeEvent
        public static void onRegisterReload(RegisterClientReloadListenersEvent event) {
            // 資源パックが替わると元の絵が変わる → 塗り替えた絵を作り直す
            event.registerReloadListener((ResourceManagerReloadListener) rm -> InfectRender.reset());
        }
    }

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
    public static final class GameBus {
        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            GenericInfectionClient.clear();
        }

        @SubscribeEvent
        public static void onLeave(EntityLeaveLevelEvent event) {
            if (event.getLevel().isClientSide()) {
                GenericInfectionClient.remove(event.getEntity().getId());
            }
        }
    }

    /**
     * 確かめ用: {@code center} から {@code radius} の中の生き物(プレイヤー以外)を、感染体として描かせる。{@code radius <= 0} で全部戻す。
     * 同期(サーバーの印)が入るまでの仮の入口。返り値 = 印を付けた数。
     */
    public static int devMark(ClientLevel level, Vec3 center, double radius) {
        GenericInfectionClient.clear();
        InfectRender.reset();
        if (radius <= 0.0D) {
            return 0;
        }
        int n = 0;
        for (Entity e : level.entitiesForRendering()) {
            if (e instanceof LivingEntity && !(e instanceof Player) && e.position().distanceToSqr(center) <= radius * radius) {
                GenericInfectionClient.put(e.getId(), e.getUUID().getLeastSignificantBits(), level.getGameTime(), 0, false);
                n++;
            }
        }
        return n;
    }
}
