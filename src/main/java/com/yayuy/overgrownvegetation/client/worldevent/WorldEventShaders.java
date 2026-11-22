package com.yayuy.overgrownvegetation.client.worldevent;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.RegisterShadersEvent;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * ワールドイベントの見た目のシェーダー(2026-11-16。{@code client.shader.ModShaders}・{@code client.sky.SkyShaders} とは分けて、ここの分だけ登録する)。
 * 読み込めなかった物は null(その見た目を描かないだけで、ほかは動く)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class WorldEventShaders {

    private static ShaderInstance aurora;

    /** 胞子のオーロラ({@link WorldEventSky})。 */
    public static ShaderInstance aurora() {
        return aurora;
    }

    private static ShaderInstance halo;
    private static ShaderInstance eclipse;

    /** 日食の地平線の光({@link Eclipse})。 */
    public static ShaderInstance eclipse() {
        return eclipse;
    }

    /** 日暈({@link WorldEventSky})。 */
    public static ShaderInstance halo() {
        return halo;
    }

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) {
        register(event, "ov_aurora", DefaultVertexFormat.POSITION, s -> aurora = s);
        register(event, "ov_halo", DefaultVertexFormat.POSITION, s -> halo = s);
        register(event, "ov_eclipse", DefaultVertexFormat.POSITION, s -> eclipse = s);
    }

    private static void register(RegisterShadersEvent event, String name, VertexFormat format, Consumer<ShaderInstance> into) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, name), format), into);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("ワールドイベントのシェーダー {} を読み込めなかったので、その見た目は描かない", name, e);
            into.accept(null);
        }
    }

    private WorldEventShaders() {
    }
}
