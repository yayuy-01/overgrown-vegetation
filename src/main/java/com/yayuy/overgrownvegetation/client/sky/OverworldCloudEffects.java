package com.yayuy.overgrownvegetation.client.sky;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import org.joml.Matrix4f;

/**
 * オーバーワールドの空の見た目(バニラの {@link DimensionSpecialEffects.OverworldEffects} そのまま)に、雲だけ今の描き方
 * ({@link CloudRenderers#current()})を差す(2026-10-17。2026-11-08 雲の段階 1 で parked から戻した。前の版のライトマップの色味の試作は外した)。
 * NeoForge の {@code renderClouds} は true を返すとバニラの雲を描かない。同じ dimension type の見た目を登録し直すとバニラのものと入れ替わる。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class OverworldCloudEffects extends DimensionSpecialEffects.OverworldEffects {

    @SubscribeEvent
    public static void onRegister(RegisterDimensionSpecialEffectsEvent event) {
        event.register(BuiltinDimensionTypes.OVERWORLD_EFFECTS, new OverworldCloudEffects());
    }

    @Override
    public boolean renderClouds(ClientLevel level, int ticks, float partialTick, PoseStack poseStack, double camX, double camY, double camZ,
                                Matrix4f modelViewMatrix, Matrix4f projectionMatrix) {
        return CloudRenderers.current().render(level, partialTick, modelViewMatrix, projectionMatrix, camX, camY, camZ);
    }

    /**
     * 雲の中に深く入ったら、空(太陽・月・星)を描かない(11-08 仕上げ 4: 雲の中で上を見ると太陽の光の筋が見えた。前の版の HIDE_SKY と同じ 0.7 より深い所)。
     * 背景は霧の色(雲の色)で塗られる({@link CloudInterior#onFogColor})。
     */
    @Override
    public boolean renderSky(ClientLevel level, int ticks, float partialTick, Matrix4f modelViewMatrix, Camera camera, Matrix4f projectionMatrix,
                             boolean isFoggy, Runnable setupFog) {
        return CloudInterior.amount(partialTick) > HIDE_SKY;
    }

    /** 空を描かなくなる雲の中の深さ(前の版の値)。 */
    static final float HIDE_SKY = 0.7F;
}
