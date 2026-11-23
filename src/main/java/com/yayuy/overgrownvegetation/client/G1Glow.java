package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * G1 の胞子の袋の中だけ光る層(2026-11-08)。GeckoLib の {@link AutoGlowingGeoLayer} は {@code <絵>_glowmask.png} が無い・光る画素が 1 つも無いと
 * 例外で落ちる(ゾンビ r9・村人 r6 などは袋が透けない版で glowmask が空)。取り込み({@code install_v2.sh})は空の glowmask を写さないので、
 * 絵が有るときだけ描く。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class G1Glow<T extends GeoAnimatable> extends AutoGlowingGeoLayer<T> {

    private static final Map<ResourceLocation, Boolean> HAS = new HashMap<>();

    private final Predicate<T> skip;

    public G1Glow(GeoRenderer<T> renderer, Predicate<T> skip) {
        super(renderer);
        this.skip = skip;
    }

    /** {@code texture} に光る絵 {@code <texture>_glowmask.png} があるか。 */
    public static boolean hasGlow(ResourceLocation texture) {
        return HAS.computeIfAbsent(texture, t -> Minecraft.getInstance().getResourceManager()
                .getResource(glowmask(t)).isPresent());
    }

    /** 1.20.1(GeckoLib 4.8.4): AutoGlowingTexture#getEmissiveResource は private(しかも絵を作る副作用つき)。名前だけ同じ決まりで作る */
    private static ResourceLocation glowmask(ResourceLocation texture) {
        String path = texture.getPath();
        int dot = path.lastIndexOf('.');
        return ResourceLocation.fromNamespaceAndPath(texture.getNamespace(), dot < 0 ? path + "_glowmask" : path.substring(0, dot) + "_glowmask" + path.substring(dot));
    }

    @Override
    public void render(PoseStack poseStack, T animatable, BakedGeoModel model, RenderType renderType, MultiBufferSource bufferSource,
                       VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
        if (skip.test(animatable) || !hasGlow(getTextureResource(animatable))) {
            return;
        }
        super.render(poseStack, animatable, model, renderType, bufferSource, buffer, partialTick, packedLight, packedOverlay);
    }

    /** 資源の読み直し(F3+T)で忘れる。 */
    @SubscribeEvent
    public static void onRegisterReload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) rm -> HAS.clear());
    }
}
