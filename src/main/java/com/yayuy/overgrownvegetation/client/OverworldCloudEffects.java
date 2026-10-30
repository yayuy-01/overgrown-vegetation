package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
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
 * オーバーワールドの空の見た目(バニラの {@link DimensionSpecialEffects.OverworldEffects} そのまま)に、雲だけ {@link VoxelClouds} を差す
 * (2026-10-17)。NeoForge の {@code renderClouds} は true を返すとバニラの雲を描かない。同じ dimension type の見た目を登録し直すと
 * バニラのものと入れ替わる({@code RegisterDimensionSpecialEffectsEvent#register} は上書き)。
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
        return VoxelClouds.render(level, partialTick, modelViewMatrix, projectionMatrix, camX, camY, camZ);
    }

    /** ライトマップの色味(2026-10-21 試作、{@link com.yayuy.overgrownvegetation.client.light.MoodLightmap})。 */
    @Override
    public void adjustLightmapColors(ClientLevel level, float partialTicks, float skyDarken, float blockLightRedFlicker, float skyLight,
                                     int pixelX, int pixelY, org.joml.Vector3f colors) {
        com.yayuy.overgrownvegetation.client.light.MoodLightmap.adjust(level, skyDarken, blockLightRedFlicker, skyLight, pixelX, pixelY, colors);
    }
}
