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
        if (on && level != null && !SodiumTerrain.unsupported()) {
            float rain = level.getRainLevel(1.0F);
            wet = MistFog.wetAfter(p.minutes(), rain);
            Vec3 sun = SunLight.sunDirection(level, 1.0F);
            float day = Mth.clamp((float) sun.y / 0.2F, 0.0F, 1.0F);
            // 雨の最中は空の明るさの照り(弱め)、上がると日の照り。夜は月明かりほどに。
            sunPart = day * (1.0F - rain);
            Vec3 light = new Vec3(0.0, 1.0, 0.0).lerp(sun, sunPart).normalize();
            lx = (float) light.x;
            ly = (float) light.y;
            lz = (float) light.z;
            highlight = p.highlight() * Mth.lerp(rain, 1.0F, p.rainHighlight()) * Mth.lerp(day, 0.15F, 1.0F);
        }
        for (ShaderInstance shader : new ShaderInstance[]{GameRenderer.getRendertypeSolidShader(), GameRenderer.getRendertypeCutoutShader(),
                GameRenderer.getRendertypeCutoutMippedShader()}) {
            if (shader != null) {
                shader.safeGetUniform("OvWet").set(wet, highlight, p.groundDarken(), p.sharpness());
                shader.safeGetUniform("OvWetDir").set(lx, ly, lz, sunPart);
                shader.safeGetUniform("OvWetView").set(p.faceOn(), p.fresnelPower(), p.nearFrom(), Math.max(p.nearFrom() + 0.1F, p.nearTo()));
            }
        }
    }

    private WetSheen() {
    }
}
