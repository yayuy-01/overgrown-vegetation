package com.yayuy.overgrownvegetation.devbridge;

import com.mojang.blaze3d.shaders.AbstractUniform;
import com.mojang.blaze3d.shaders.Uniform;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.FloatBuffer;
import java.util.function.Supplier;

/**
 * 開発用: 地形のシェーダーの演出を uniform の強さ 0 で消す(2026-10-23、軽量化の段階 1「演出の GPU 代」を測る)。
 *
 * <p>演出のクラス(雰囲気づくりのセッションが毎日直す)には触らず、それらが AFTER_SKY で値を渡した<b>後</b>(LOWEST)に強さの成分だけ 0 で上書きする。
 * Sodium の差し込みはバニラのシェーダーの値を写すので、こちらも 0 になる。シェーダーの中の早抜け(強さ 0 なら return)がそのまま効く。
 *
 * <p>ビット: 1 揺れ(OvBase.w)・2 葉の色(OvLeafTone.xy)・4 林冠の光(OvSunA.z・OvTint.w・OvFleck.w)・8 雲の影(OvCloudA.w)・16 洞窟の床(OvCave.w)・
 * 32 極相の照り返し(OvClimax.w)・64 朝霧(OvMistB.x)・128 洞窟の霧(OvCaveB.x)・256 高さの霧 = 二色の霧(OvAerialA.w)。511 で全部。
 */
final class TerrainFxMask {

    static volatile int mask;
    private static boolean registered;

    @SuppressWarnings("unchecked")
    private static final Supplier<ShaderInstance>[] SHADERS = new Supplier[]{
            GameRenderer::getRendertypeSolidShader,
            GameRenderer::getRendertypeCutoutMippedShader,
            GameRenderer::getRendertypeCutoutShader,
            GameRenderer::getRendertypeTranslucentShader};

    static String command(String arg) {
        String a = arg.trim();
        if (a.equalsIgnoreCase("dump")) {
            // 葉の層のシェーダーに今渡っている演出の値(重さが時間で変わる件の切り分け用)
            ShaderInstance shader = GameRenderer.getRendertypeCutoutMippedShader();
            if (shader == null) {
                return "FXMASK dump シェーダーなし";
            }
            StringBuilder sb = new StringBuilder("FXMASK dump");
            for (String name : new String[]{"OvSunA", "OvTint", "OvFleck", "OvMapA", "OvMistA", "OvMistB", "OvCaveB", "OvCloudA", "OvAerialA", "OvBase"}) {
                AbstractUniform au = shader.safeGetUniform(name);
                if (au instanceof Uniform u && u.getFloatBuffer() != null) {
                    FloatBuffer b = u.getFloatBuffer();
                    sb.append(String.format(" %s=(%.3g,%.3g,%.3g,%.3g)", name, b.get(0), b.get(1), b.get(2), b.get(3)));
                }
            }
            return sb.toString();
        }
        if (!a.isEmpty()) {
            mask = a.equalsIgnoreCase("off") ? 0 : a.equalsIgnoreCase("all") ? 511 : Integer.parseInt(a);
        }
        if (!registered) {
            registered = true;
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, TerrainFxMask::onStage);
        }
        return "FXMASK " + mask;
    }

    private static void onStage(RenderLevelStageEvent event) {
        int m = mask;
        if (m == 0 || event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) {
            return;
        }
        for (Supplier<ShaderInstance> s : SHADERS) {
            ShaderInstance shader = s.get();
            if (shader == null) {
                continue;
            }
            if ((m & 1) != 0) zero(shader, "OvBase", 3);
            if ((m & 2) != 0) {
                zero(shader, "OvLeafTone", 0);
                zero(shader, "OvLeafTone", 1);
            }
            if ((m & 4) != 0) {
                zero(shader, "OvSunA", 2);
                zero(shader, "OvTint", 3);
                zero(shader, "OvFleck", 3);
            }
            if ((m & 8) != 0) zero(shader, "OvCloudA", 3);
            if ((m & 16) != 0) zero(shader, "OvCave", 3);
            if ((m & 32) != 0) zero(shader, "OvClimax", 3);
            if ((m & 64) != 0) zero(shader, "OvMistB", 0);
            if ((m & 128) != 0) zero(shader, "OvCaveB", 0);
            if ((m & 256) != 0) zero(shader, "OvAerialA", 3);
        }
    }

    private static void zero(ShaderInstance shader, String name, int index) {
        AbstractUniform au = shader.safeGetUniform(name);
        if (!(au instanceof Uniform u)) {
            return;
        }
        FloatBuffer b = u.getFloatBuffer();
        if (b == null || b.capacity() < 4) {
            return;
        }
        float[] v = {b.get(0), b.get(1), b.get(2), b.get(3)};
        v[index] = 0.0F;
        u.set(v[0], v[1], v[2], v[3]);
    }

    private TerrainFxMask() {
    }
}
