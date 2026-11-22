package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.client.shader.SodiumTerrain;
import com.yayuy.overgrownvegetation.data.AtmosphereLife;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * <b>濡れた照り</b>(一覧 38、2026-10-31 見え方の段。設定: 湿度の高い熱帯の森は雨のたびに濡れ、雨上がりの日差しで覆い・葉・幹が光る)。
 * 雨の最中と雨上がり({@link MistFog#wetAfter}。止んでから {@code wet.minutes} 分、10-31 ユーザー「3 分程度」)に、空の光の届く面へ薄いハイライトを乗せ、上向きの面(地面)を少し暗くする。
 * 式は {@code shaders/include/ov_wet.glsl}(バニラの solid・cutout・cutout_mipped と Sodium の地形)。ハイライトは視線と光の向きの半ベクトル:
 * 雨の最中は明るい空(真上)、上がって日が出たら太陽へ寄せる。値は {@code atmosphere.json} の節 {@code wet}(仮決め)。
 */
final class WetSheen {

    /** 過繁茂の外の濡れ(0〜1。11-06 バニラ地域の光 5)と前のフレームの時刻。 */
    private static float openWet;
    private static long lastNanos;

    /** 確かめ用: 過繁茂の外の濡れ。 */
    static float openWet() {
        return openWet;
    }

    /** AFTER_SKY に uniform を流す({@code on} が false なら 0。Sodium へは SodiumTerrain が写す)。 */
    static void upload(boolean on) {
        on = on && VisualQuality.waterLife();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        float wet = 0.0F;
        float highlight = 0.0F;
        float lx = 0.0F;
        float ly = 1.0F;
        float lz = 0.0F;
        float sunPart = 0.0F;
        AtmosphereLife.WetParams p = DesignData.atmosphere().wet();
        float overgrown = 0.0F;
        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 0.0F : Mth.clamp((now - lastNanos) / 1.0e9F, 0.0F, 1.0F);
        lastNanos = now;
        if (on && level != null && !SodiumTerrain.unsupported()) {
            float rain = level.getRainLevel(1.0F);
            // 11-06 バニラ地域の光 5: 過繁茂の外は、雨が降っている間に rainWetIn 秒で濡れ、止んで rainWetOut 秒で乾く。雪の降る所・降らない所
            // (カメラの所のバイオーム)は濡れない。過繁茂の中は前の規則(MistFog.wetAfter: 止んでから minutes 分)。カメラの過繁茂の度合いで混ぜる。
            var cam = mc.gameRenderer.getMainCamera().getBlockPosition();
            boolean rains = rain > 0.01F && level.getBiome(cam).value().getPrecipitationAt(cam) == net.minecraft.world.level.biome.Biome.Precipitation.RAIN;
            float target = rains ? rain : 0.0F;
            openWet = target > openWet ? Math.min(target, openWet + dt / p.rainWetIn()) : Math.max(target, openWet - dt / p.rainWetOut());
            overgrown = ForestPresence.overgrown();
            wet = Mth.lerp(overgrown, SunLight.smooth(openWet), MistFog.wetAfter(p.minutes(), rain));
            Vec3 sun = SunLight.sunDirection(level, 1.0F);
            float day = Mth.clamp((float) sun.y / 0.2F, 0.0F, 1.0F);
            // 雨の最中は空の明るさの照り(弱め)、上がると日の照り。夜は月明かりほどに。
            sunPart = day * (1.0F - rain);
            Vec3 light = new Vec3(0.0, 1.0, 0.0).lerp(sun, sunPart).normalize();
            lx = (float) light.x;
            ly = (float) light.y;
            lz = (float) light.z;
            highlight = Mth.lerp(overgrown, p.rainWetSpecular(), p.highlight()) * Mth.lerp(rain, 1.0F, p.rainHighlight()) * Mth.lerp(day, 0.15F, 1.0F);
        }
        float darken = Mth.lerp(overgrown, 1.0F - p.rainWetDarken(), p.groundDarken());
        for (ShaderInstance shader : new ShaderInstance[]{GameRenderer.getRendertypeSolidShader(), GameRenderer.getRendertypeCutoutShader(),
                GameRenderer.getRendertypeCutoutMippedShader()}) {
            if (shader != null) {
                shader.safeGetUniform("OvWet").set(wet, highlight, darken, p.sharpness());
                shader.safeGetUniform("OvWetB").set(Mth.lerp(overgrown, p.rainWetSkyFrom() / 15.0F, 0.25F), Mth.lerp(overgrown, p.rainWetSkyTo() / 15.0F, 0.75F),
                        1.0F - overgrown, Mth.lerp(overgrown, p.rainWetSaturate(), 0.0F));
                shader.safeGetUniform("OvWetDir").set(lx, ly, lz, sunPart);
                shader.safeGetUniform("OvWetView").set(p.faceOn(), p.fresnelPower(), p.nearFrom(), Math.max(p.nearFrom() + 0.1F, p.nearTo()));
            }
        }
    }

    private WetSheen() {
    }
}
