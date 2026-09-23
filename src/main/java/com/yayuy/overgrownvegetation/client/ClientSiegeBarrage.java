package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.SiegeBarragePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 触手の小ビームの<b>乱射</b>のクライアント側の状態。1 発ごとに
 * {@link SiegeBarragePayload} が届き、{@value #SHOT_TICKS} tick だけ描いて消える(描画は
 * {@link SiegeBeamRenderer})。
 *
 * <p>花のビームや 1 発ずつの触手ビームと違って「段階」を持たないので、
 * {@code SiegeBossStatePayload} には載せず、ここに短命の一覧として持つ。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ClientSiegeBarrage {

    /** 1 発を描いている長さ(tick)。 */
    public static final int SHOT_TICKS = 7;

    /** 乱射の 1 発。 */
    public record Shot(Vec3 from, Vec3 to, long startGameTime) {
    }

    private static final List<Shot> SHOTS = new ArrayList<>();

    public static void onShot(SiegeBarragePayload payload) {
        SHOTS.add(new Shot(payload.from(), payload.to(), payload.startGameTime()));
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (SHOTS.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            SHOTS.clear();
            return;
        }
        long now = minecraft.level.getGameTime();
        Iterator<Shot> shots = SHOTS.iterator();
        while (shots.hasNext()) {
            if (now - shots.next().startGameTime() > SHOT_TICKS) {
                shots.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        SHOTS.clear();
    }

    public static List<Shot> shots() {
        return SHOTS;
    }

    private ClientSiegeBarrage() {
    }
}
