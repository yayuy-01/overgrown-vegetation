package com.yayuy.overgrownvegetation.client.atmosphere.heat;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * <b>熱水の森(ネザーの汚染地)の見え方の束ね役</b>(2026-11-10 熱水の森のシェーダー段階 1)。オーバーワールドの雰囲気の束ね役
 * ({@code client/atmosphere/Atmosphere})は空の光のある次元だけで動くので、ネザーはここで受ける。
 * <ul>
 *     <li>毎 tick: 熱水の森の中にいる度合い({@link HeatFxPresence})。</li>
 *     <li>ライトマップ: 1. 光らない面を暗くする({@link HeatFxShade}。読み手は {@code LightTextureCanopyLiftMixin})。</li>
 *     <li>霧の色・距離: 2. 深い青緑〜黒の霧({@link HeatFxFog})。</li>
 * </ul>
 * 3(光の息づき・偽の空の昼夜)・4(光の帯)・5(かげろう)は後の段階でここへ足す。確かめ用の口は {@link HeatFxDev}(DevBridge の {@code fog heat …})。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class HeatFx {

    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");
    private static boolean broken;
    private static long ticks;

    private HeatFx() {
    }

    /** 熱水の森の見え方を掛けるか(ネザー・雰囲気の設定・シェーダーパック使用中は設定次第)。 */
    public static boolean enabled() {
        ClientLevel level = Minecraft.getInstance().level;
        return !broken && level != null && level.dimension() == Level.NETHER && VisualQuality.atmosphere()
                && (!ShaderPacks.active() || ShaderPacks.ownEffects());
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        // 光る面の目印を返すか(11-10 段階 2)。ネザーの外・シェーダーパック使用中も決める(変わったら組み直す)。
        try {
            HeatFxGlow.tick(mc, !broken);
        } catch (RuntimeException e) {
            fail(e);
        }
        if (mc.level == null || mc.player == null || !enabled()) {
            HeatFxPresence.clear();
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        try {
            HeatFxPresence.tick(mc.level, mc.gameRenderer.getMainCamera().getPosition());
            ticks++;
            if (DEV_LOG && ticks % 100 == 0) {
                OvergrownVegetation.LOGGER.info("HeatFx {}", HeatFxDev.status());
            }
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    @SubscribeEvent
    public static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        if (!enabled()) {
            return;
        }
        try {
            HeatFxFog.computeFogColor(event);
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (!enabled()) {
            return;
        }
        try {
            HeatFxFog.renderFog(event);
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        try {
            if (event.getStage() == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_SKY) {
                // 地形のシェーダーへの霧(ネザーの外では 0 を入れて止める)
                HeatFxFog.upload(enabled());
                // 光る面の階級ごとの明るさ(昼夜・脈動。11-10 段階 2)
                HeatFxGlow.upload(event.getPartialTick().getGameTimeDeltaPartialTick(false));
            } else if (event.getStage() == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS && enabled()) {
                if (DEV_LOG) {
                    HeatFxFog.noteAfterSolid();
                }
                HeatFxFog.afterSolid();
            }
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    /** 例外が出たら、このセッションでは熱水の森の見え方を止める。 */
    private static void fail(RuntimeException e) {
        broken = true;
        HeatFxPresence.clear();
        OvergrownVegetation.LOGGER.error("熱水の森の見え方で例外が出たので、このセッションでは止める", e);
    }
}
