package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import com.yayuy.overgrownvegetation.data.AtmosphereEye;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.RenderLevelStageEvent;

/**
 * <b>濡れた岩の照り</b>(2026-11-14 シェーダーの仕上げ F の 1。11-07 採用「地下モードの見え方」の 1 つ。ユーザー決定: 世界全体の洞窟)。
 *
 * <p>理由: 洞窟の岩は湿っている。点光源(光る植物・松明・溶岩)や手に持った明かりの映り込みが岩の面にやわらかく照ると、暗い洞窟に奥行きと湿り気が出る。
 *
 * <p>やり方: 地形のシェーダーの点光源の計算({@code ov_cave.glsl} の ovCaveLight)の中で、不透明のブロックの面(葉・草は除く)に、光源ごとの
 * 映り込み(Blinn の半ベクトル)を足す。地下とみなした面だけ。強さ {@code strength}・鋭さ {@code shininess} は design の節 {@code caveWet}(仮決め)。
 * 見え方の設定「地下の光」を切ると出ない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class CaveWetRock {

    private CaveWetRock() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) {
            return;
        }
        ShaderInstance shader = GameRenderer.getRendertypeSolidShader();
        if (shader == null) {
            return;
        }
        AtmosphereEye.CaveWetParams p = DesignData.atmosphere().caveWet();
        boolean on = p.enabled() && VisualQuality.caveLight() && !ShaderPacks.active();
        shader.safeGetUniform("OvCaveWet").set(on ? p.strength() : 0.0F, p.shininess(), 0.0F, 0.0F);
    }
}
