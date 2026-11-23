package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import com.yayuy.overgrownvegetation.data.AtmosphereEye;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.RenderLevelStageEvent;

/**
 * <b>テクスチャの明暗から面の凹凸</b>(2026-11-14 シェーダーの仕上げ G。ユーザー決定: 作るが<b>既定でオフ</b>、設定で入れる)。
 *
 * <p>理由: 樹皮・岩・板は平らな面で、低い日差しでも凹凸が出ない。テクスチャの明るさを高さとして読み、太陽の向きへの傾きで明暗を付けると、
 * 朝夕に木目や岩肌が浮く。既定でオフなのは、資源パックとの相性が危ういため(16×16 の絵がアトラスの 16 画素の升に並んでいる前提。
 * 大きな絵の資源パックでは 16 画素ごとに平らな継ぎ目が出る)。
 *
 * <p>やり方({@code ov_bump.glsl}): 不透明のブロックの面で、隣の画素との明るさの差(u・v の向き)を世界の向きへ直し、太陽の向きとの内積を明暗にする。
 * 光が面に斜めに当たるほど強く、真上からだと出ない。昼の太陽だけ(夜・雨はその分弱まる)、空の光が届く面だけ、カメラの近くだけ
 * ({@code distance} で消える)。見え方の設定「面の凹凸」({@code surfaceBump}、既定オフ)。数は design の節 {@code surfaceBump}(仮決め)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class SurfaceBump {

    private SurfaceBump() {
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
        AtmosphereEye.SurfaceBumpParams p = DesignData.atmosphere().surfaceBump();
        ClientLevel level = Minecraft.getInstance().level;
        float strength = 0.0F;
        if (level != null && p.strength() > 0.0F && VisualQuality.surfaceBump() && !ShaderPacks.active() && level.dimensionType().hasSkyLight()) {
            float pt = event.getPartialTick();
            // 昼の太陽の分だけ(夜は 0。雨・夕方の弱まりは daylight に入っている)。
            strength = p.strength() * SunLight.daylight(level, SunLight.sunDirection(level, pt), pt);
        }
        shader.safeGetUniform("OvBump").set(strength, p.distance(), 0.0F, 0.0F);
    }
}
