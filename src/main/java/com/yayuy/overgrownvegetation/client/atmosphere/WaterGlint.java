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
 * <b>水面の太陽と月</b>(2026-11-06 バニラ地域の光 4。理由: バニラの水は空の色の平らな板で、夕日も月も映らない。開けた水辺がいちばんきれいに見える場面)。
 * 世界全体の水(半透明の層の上向きの面)に、昼は太陽・夜は月の照り(Blinn)を足す。水面はゆっくりの波 4 つで傾け、照りが光の道のようにきらめく。
 * 林冠・屋根の下(頂点の空の光 14/15 未満)・太陽の地図で陰の所(Sodium だけ)・地下には出さない(地下は {@code ov_cave.glsl} の点光源の照り)。
 * 色は昼は時刻の色({@link SunLight#skyTint}。夕方は金〜桃)、夜は {@code waterGlint.moonColor}。雨の間は波を荒く、照りを弱く(雲)。
 * 式は {@code shaders/include/ov_water.glsl}(バニラの半透明と Sodium の地形)。値は {@code atmosphere.json} の節 {@code waterGlint}(仮決め)。
 */
final class WaterGlint {

    private static final float[] TINT = new float[3];
    /** 照りの色の彩度を時刻の色の何倍にするか(仮決め)。 */
    private static final float TINT_BOOST = 2.0F;

    /** AFTER_SKY に uniform を流す({@code on} が false なら強さ 0。Sodium へは SodiumTerrain が写す)。 */
    static void upload(boolean on) {
        on = on && VisualQuality.waterLife();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        AtmosphereLife.WaterGlintParams p = DesignData.atmosphere().waterGlint();
        float strength = 0.0F;
        float dx = 0.0F;
        float dy = 1.0F;
        float dz = 0.0F;
        float r = 1.0F;
        float g = 1.0F;
        float b = 1.0F;
        float ripple = p.ripple();
        float time = 0.0F;
        float camX = 0.0F;
        float camZ = 0.0F;
        if (on && level != null && !SodiumTerrain.unsupported()) {
            float pt = mc.getTimer().getGameTimeDeltaPartialTick(false);
            Vec3 sun = SunLight.sunDirection(level, pt);
            float rain = level.getRainLevel(pt);
            float day = SunLight.smooth(Mth.clamp(((float) sun.y + 0.02F) / 0.08F, 0.0F, 1.0F));
            float night = SunLight.smooth(Mth.clamp(((float) -sun.y - 0.02F) / 0.08F, 0.0F, 1.0F));
            Vec3 dir = day > 0.0F || night <= 0.0F ? sun : sun.scale(-1.0);
            dx = (float) dir.x;
            dy = (float) dir.y;
            dz = (float) dir.z;
            if (day > 0.0F) {
                // 時刻の色は明るさ 1 で彩度が控えめ(面全体に掛けるため)。じかに映る太陽はもっと色が濃いので、彩度を TINT_BOOST 倍にする(仮決め)。
                SunLight.skyTint(level, sun, pt, TINT);
                float lum = 0.299F * TINT[0] + 0.587F * TINT[1] + 0.114F * TINT[2];
                r = Math.max(0.0F, lum + (TINT[0] - lum) * TINT_BOOST);
                g = Math.max(0.0F, lum + (TINT[1] - lum) * TINT_BOOST);
                b = Math.max(0.0F, lum + (TINT[2] - lum) * TINT_BOOST);
                strength = p.sunSpecular() * day;
            } else {
                float[] m = p.moonColor();
                r = m[0];
                g = m[1];
                b = m[2];
                strength = p.moonSpecular() * night * level.getMoonBrightness();
            }
            strength *= Mth.lerp(rain, 1.0F, p.rainDim());
            ripple *= Mth.lerp(rain, 1.0F, p.rainRipple());
            // Atmosphere.time は tick(秒でない。11-06 雨の見え方の作業で気づいた: 前は波が 20 倍速く動いていた)。
            time = (float) ((Atmosphere.time(pt) / 20.0) % 600.0);
            Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
            camX = (float) Mth.positiveModulo(cam.x, 1024.0);
            camZ = (float) Mth.positiveModulo(cam.z, 1024.0);
        }
        ShaderInstance shader = GameRenderer.getRendertypeTranslucentShader();
        if (shader != null) {
            shader.safeGetUniform("OvWaterSpec").set(dx, dy, dz, strength);
            shader.safeGetUniform("OvWaterSpecB").set(r, g, b, p.shininess());
            shader.safeGetUniform("OvWaterSpecC").set(ripple, time, camX, camZ);
        }
    }

    private WaterGlint() {
    }
}
