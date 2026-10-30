package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * <b>二色の霧(空気遠近法)</b>(2026-10-17。10-16 にユーザーが選んだ「密林をさらに美しく見せる案」の (2)「近くは暖かく遠くは青緑へ。
 * 遠くほど青く淡くなる空気遠近法で奥行きが出る」)。
 *
 * <p>地形のシェーダーが霧を掛けるとき、霧の色を距離で混ぜる({@code ov_mist.glsl} の {@code ovAerialFog}): 近い所(霧の終わりの 15〜90%)ほど
 * 日に照らされた暖かい色へ寄せ、遠くは今の霧の色(森の青緑。空・背景と同じなので霧の終わりに継ぎ目が出ない)のまま。太陽の方ほど暖かい(光が前へ散る)。
 * 暖かい色は、今の霧の色の明るさのまま色合いだけ金色へ回したもの(霧の色が朝夕・天気で変わっても明るさがそろう)。
 *
 * <p>昼だけ(太陽の高さで寄せる)・過繁茂の森にいる度合い × (1 − 地下の度合い)・極相の密林では半分(光を吸う青緑の森)。
 * 地形だけに掛かる(Mob・粒・空はバニラの霧のまま。近い所は霧が薄いので差は小さい)。Sodium・Iris では出ない。設定 {@code aerialFog}。数値は仮決め。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class AerialFog {

    /** 暖かい色の色合い(明るさをそろえる前の比)。仮決め。 */
    private static final float WARM_R = 1.2F;
    private static final float WARM_G = 1.0F;
    private static final float WARM_B = 0.6F;
    /** 暖かい色を霧の色よりどれだけ明るくするか。仮決め。 */
    private static final float WARM_BRIGHTER = 1.1F;
    /** 太陽の方をどれだけ暖かくするか(0 = 向きに関係なく同じ)。仮決め。 */
    private static final float SUN_SIDE = 0.7F;
    /** 遠い側(霧の色そのもの)を青緑へ回す割合(強さ 1 のとき)と、その色合い(明るさ 1 にそろえた比 0.80 : 1.00 : 1.12)。仮決め。 */
    static final float COOL = 0.45F;
    static final float COOL_R = 0.839F;
    static final float COOL_G = 1.048F;
    static final float COOL_B = 1.174F;

    private static boolean broken;

    /**
     * 二色の霧の強さ(0〜1): 設定 × 昼(太陽の高さで寄せる)× 過繁茂の森にいる度合い × (1 − 地下の度合い)× 極相の密林では半分。
     * 太陽の向きを {@code sunOut} に入れる(長さ 3 の配列、null 可)。
     */
    static float strength(ClientLevel level, float partialTick, float[] sunOut) {
        float setting = OvergrownVegetationConfig.COMMON.aerialFog.get().floatValue();
        if (level == null || setting <= 0.0F) {
            return 0.0F;
        }
        Vec3 sun = CanopyLight.sunDirection(level, partialTick);
        if (sunOut != null) {
            sunOut[0] = (float) sun.x;
            sunOut[1] = (float) sun.y;
            sunOut[2] = (float) sun.z;
        }
        float day = CanopyLight.smooth(Mth.clamp((float) (sun.y + 0.05) / 0.3F, 0.0F, 1.0F));
        return setting * day * CorruptionAtmosphere.forestFactor() * (1.0F - CaveLight.underground())
                * (1.0F - 0.5F * CorruptionAtmosphere.jungleClimax());
    }

    /** 霧の色(遠い側)を青緑へ回す割合({@link CorruptionAtmosphere#onComputeFogColor} が読む)。 */
    static float coolAmount(ClientLevel level, float partialTick) {
        return COOL * strength(level, partialTick, null);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || broken || SodiumTerrain.unsupported()) {
            return;
        }
        try {
            apply(event);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("二色の霧で例外が出たので、このセッションでは使わない", e);
        }
    }

    private static void apply(RenderLevelStageEvent event) {
        ClientLevel level = Minecraft.getInstance().level;
        float[] sun = {0.0F, 1.0F, 0.0F};
        float strength = strength(level, event.getPartialTick().getGameTimeDeltaPartialTick(false), sun);
        float[] fog = RenderSystem.getShaderFogColor();
        float lum = 0.299F * fog[0] + 0.587F * fog[1] + 0.114F * fog[2];
        float k = WARM_BRIGHTER * lum / (0.299F * WARM_R + 0.587F * WARM_G + 0.114F * WARM_B);
        float r = Math.min(1.0F, WARM_R * k);
        float g = Math.min(1.0F, WARM_G * k);
        float b = Math.min(1.0F, WARM_B * k);
        if (com.yayuy.overgrownvegetation.client.light.MoodState.colorOwned()) {
            // 画面の色の調子(2026-10-21、client/light/MoodState)が色を持つ間は、近い側も明るさだけ(同じ明るさの灰色)。
            r = g = b = Math.min(1.0F, WARM_BRIGHTER * lum);
        }
        ShaderInstance[] shaders = {GameRenderer.getRendertypeSolidShader(), GameRenderer.getRendertypeCutoutShader(),
                GameRenderer.getRendertypeCutoutMippedShader(), GameRenderer.getRendertypeTranslucentShader()};
        for (ShaderInstance shader : shaders) {
            if (shader == null) {
                continue;
            }
            shader.safeGetUniform("OvAerialA").set(r, g, b, strength);
            shader.safeGetUniform("OvAerialB").set(sun[0], sun[1], sun[2], SUN_SIDE);
        }
    }

    private AerialFog() {
    }
}
