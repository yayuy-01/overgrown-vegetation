package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * カメラが<b>過繁茂の森の中にいる度合い</b>と<b>岩の天井の下にいる度合い</b>(どちらも 0〜1)を測るだけの所(2026-10-30 の整理(退避))。
 *
 * <p>前は森の霧({@code CorruptionAtmosphere#forestFactor})・地下の光({@code CaveLight#underground})・高空の晴天
 * ({@code HighSky})がそれぞれ持っていた値。霧と空の色の演出は {@code parked/} へ退避したが、残した林冠の光・木漏れ日・胞子の粒が
 * この 2 つの値で強さを決めているので、測る所だけをここへ写した(式は元と同じ。寄せの速さは霧の {@code fogFadeSeconds})。
 * 霧の演出を戻すときは、元のクラスの値へ戻してこのクラスを消してよい。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class ForestPresence {
    private static final int ROOF_SCAN = 4096;

    private static float forest;
    private static float underground;
    private static boolean roofed;
    private static long ticks;
    private static long lastMillis;

    /** 過繁茂の森の中にいる度合い(湿地は {@code swampFogFactor} まで)。高空の晴天の高さからは外す。 */
    public static float forestFactor() {
        return forest * (1.0F - highSkyClarity());
    }

    /** カメラが岩の天井の下にいる度合い。 */
    public static float underground() {
        return underground;
    }

    /** 高空の晴天が始まる高さ(config の {@code highSkyClearFrom})。 */
    public static float highSkyFrom() {
        return OvergrownVegetationConfig.COMMON.highSkyClearFrom.get().floatValue();
    }

    /** 高空の晴天の度合い(高さ {@code highSkyClearFrom}〜{@code highSkyClearTo} で 0 → 1)。 */
    public static float highSkyClarity() {
        double setting = OvergrownVegetationConfig.COMMON.highSkyClarity.get();
        if (setting <= 0.0) {
            return 0.0F;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.gameRenderer == null) {
            return 0.0F;
        }
        double y = mc.gameRenderer.getMainCamera().getPosition().y;
        float from = highSkyFrom();
        float to = Math.max(from + 1.0F, OvergrownVegetationConfig.COMMON.highSkyClearTo.get().floatValue());
        return (float) setting * CanopyLight.smooth(Mth.clamp((float) ((y - from) / (to - from)), 0.0F, 1.0F));
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || mc.isPaused()) {
            underground = 0.0F;
            return;
        }
        ticks++;
        if (ticks % 10 == 0) {
            roofed = hasRockRoof(level, mc.gameRenderer.getMainCamera().getPosition());
        }
        float roofTarget = roofed ? 1.0F : 0.0F;
        underground = roofTarget > underground ? Math.min(roofTarget, underground + 0.05F) : Math.max(roofTarget, underground - 0.05F);

        var biome = level.getBiome(mc.player.blockPosition());
        float target = 0.0F;
        if (ModBiomes.isOvergrownLike(biome)) {
            target = ModBiomes.isSwampLike(biome) ? OvergrownVegetationConfig.COMMON.swampFogFactor.get().floatValue() : 1.0F;
        }
        long now = Util.getMillis();
        float elapsed = lastMillis == 0L ? 0.0F : (now - lastMillis);
        lastMillis = now;
        float fade = (float) (OvergrownVegetationConfig.COMMON.fogFadeSeconds.get() * 1000.0D);
        float step = fade > 0.0F ? elapsed / fade : 1.0F;
        forest = forest < target ? Math.min(target, forest + step) : Math.max(target, forest - step);
    }

    /** {@code eye} の上に、木の一部でない不透明なブロックがあるか(洞窟・建物の中)。木の一部は飛ばしてその上を見る。 */
    static boolean hasRockRoof(ClientLevel level, Vec3 eye) {
        if (!level.dimensionType().hasSkyLight()) {
            return true;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int x = (int) Math.floor(eye.x);
        int z = (int) Math.floor(eye.z);
        int y0 = (int) Math.floor(eye.y) + 1;
        int top = Math.min(level.getMaxBuildHeight() - 1, y0 + ROOF_SCAN);
        for (int y = y0; y <= top; y++) {
            BlockState state = level.getBlockState(pos.set(x, y, z));
            if (state.isAir() || !state.canOcclude() || TreeCanopyGroundFinder.isTreePart(state)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private ForestPresence() {
    }
}
