package com.yayuy.overgrownvegetation.client.wall;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 蠢く壁を描く差し込み口(2026-11-16 試作)。描き方は {@link #renderer} に任せる(差し替えられる)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CreepingWallRendering {

    /** 今の描き方。見た目を作り直すときはここを替える。 */
    public static CreepingWallRenderer renderer = new BoxCreepingWallRenderer();

    private CreepingWallRendering() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientCreepingWall.tick();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || ClientCreepingWall.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        float time = mc.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        renderer.render(event, mc.level, event.getCamera(), ClientCreepingWall.views(), time);
    }
}
