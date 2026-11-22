package com.yayuy.overgrownvegetation.client.wall;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.event.tick.ClientTickEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;

/**
 * 蠢く壁を描く差し込み口(2026-11-16 試作)。描き方は {@link #renderer} に任せる(差し替えられる)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class CreepingWallRendering {

    /**
     * 今の描き方。見た目を作り直すときはここを替える。
     * 11-16 夜: ユーザーが案 B「飲まれた者たち」を選んだので {@link TorrentCreepingWallRenderer} に(仮の箱 {@link BoxCreepingWallRenderer} は比べ用に残す)。
     */
    public static CreepingWallRenderer renderer = new TorrentCreepingWallRenderer();

    private CreepingWallRendering() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientCreepingWall.tick();
    }

    /**
     * 前触れと、近くで動いている間は、洞窟の環境音・水滴・水の音を消す(決定 その 1「洞窟の環境音・水滴が急に止む」)。
     */
    @SubscribeEvent
    public static void onPlaySound(net.minecraftforge.client.event.sound.PlaySoundEvent event) {
        if (event.getSound() == null || !ClientCreepingWall.isQuiet()) {
            return;
        }
        String path = event.getSound().getLocation().getPath();
        if (event.getSound().getSource() == net.minecraft.sounds.SoundSource.AMBIENT || path.startsWith("block.pointed_dripstone.drip")
                || path.equals("block.water.ambient") || path.startsWith("ambient.")) {
            event.setSound(null);
        }
    }

    /** 塊を殴った: 視線が塊の升に当たったら(後ろのブロックより手前なら)サーバーへ知らせ、後ろのブロックは叩かない。 */
    @SubscribeEvent
    public static void onAttack(net.minecraftforge.client.event.InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack() || ClientCreepingWall.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        double reach = mc.player.blockInteractionRange();
        if (mc.hitResult != null && mc.hitResult.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            reach = Math.min(reach, mc.hitResult.getLocation().distanceTo(mc.player.getEyePosition()) + 0.05D);
        }
        net.minecraft.core.BlockPos cell = ClientCreepingWall.raycast(mc.player.getEyePosition(), mc.player.getLookAngle(), reach);
        if (cell != null) {
            com.yayuy.overgrownvegetation.port.network.PacketDistributor.sendToServer(new com.yayuy.overgrownvegetation.network.CreepingWallHitPayload(cell));
            event.setCanceled(true);
            event.setSwingHand(true);
        }
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
