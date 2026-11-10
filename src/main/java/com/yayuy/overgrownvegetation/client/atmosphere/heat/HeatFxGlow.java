package com.yayuy.overgrownvegetation.client.atmosphere.heat;

import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import com.yayuy.overgrownvegetation.client.shader.SodiumTerrain;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.HeatFxTable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * <b>熱水の森の光る面: 目印・脈動・偽の空の昼夜</b>(2026-11-10 熱水の森のシェーダー段階 2。ユーザー了承「3 番 = 光る面の脈動と偽の空の昼夜」)。
 *
 * <p>光る面の tintindex が階級(1 空の先と粒・2 地の目印・3 地の粒・4 地・5 空の体)。このシェーダーが使えるときは、
 * {@code client/HeatForestGlow} がその面に<b>目印の色</b>(青が最大・緑/青 = 0.8・赤/青 = 0.9 − 0.1 × 階級)を返し、地形の頂点シェーダー
 * ({@code include/ov_heat_glow.glsl}。Sodium の {@code block_layer_opaque.vsh} とバニラの {@code rendertype_cutout.vsh}。光る面はみな切り抜きの層)
 * が階級を読んで灰色の陰に戻し、階級ごとの明るさを掛ける。チャンクに焼くのは目印だけで、時刻・脈動は uniform なので組み直さない。
 * 階級は tintindex の番号だけで決まる(新しいブロックも {@code ModBlocks.heatForestBlocks()} に入れば、こちらの手直しは要らない)。
 *
 * <p>シェーダーが使えないとき(シェーダーパック使用中・Sodium への差し込みが無い・設定で切った)は白を返す(目印の色が素のまま見えないように)。
 * 切り替わったら、ネザーにいればチャンクを組み直す(次の tick に。Sodium の描画の最中に組み直すと落ちる、メモリ sodium-iris-compat 罠 5)。
 */
public final class HeatFxGlow {

    /** 確かめ用: 目印・脈動を止める。 */
    static boolean devCodesOff;
    static boolean devPulseOff;
    private static volatile boolean codesOn;
    private static long ticks;

    private HeatFxGlow() {
    }

    /** 光る面に目印の色を返すか({@code HeatForestGlow} が読む。チャンクを組むスレッドから呼ばれる)。 */
    public static boolean codesOn() {
        return codesOn;
    }

    /** 目印の色(ARGB)。階級 1〜5。 */
    public static int codeColor(int tintIndex) {
        int r = Math.round(255.0F * (0.9F - 0.1F * tintIndex));
        return 0xFF000000 | r << 16 | 204 << 8 | 255;
    }

    /** 毎 tick: 目印を返すかを決め、変わったらチャンクを組み直す。 */
    static void tick(Minecraft mc, boolean allowed) {
        if (!mc.isPaused()) {
            ticks++;
        }
        boolean want = allowed && DesignData.heatFx().glow().codes && !devCodesOff && VisualQuality.atmosphere() && !ShaderPacks.active()
                && !SodiumTerrain.unsupported();
        if (want != codesOn) {
            codesOn = want;
            if (mc.level != null && mc.level.dimension() == Level.NETHER) {
                mc.tell(() -> mc.levelRenderer.allChanged());
            }
        }
    }

    /** AFTER_SKY: 地形のシェーダー(バニラの切り抜きの層。Sodium はそこから写す)へ渡す。 */
    static void upload(float partialTick) {
        ShaderInstance s = GameRenderer.getRendertypeCutoutShader();
        if (s == null) {
            return;
        }
        HeatFxTable.Glow g = DesignData.heatFx().glow();
        float day = HeatFxSky.day();
        float dusk = HeatFxSky.dusk();
        float[] gain = new float[5];
        for (int i = 0; i < 5; i++) {
            gain[i] = Mth.lerp(day, g.nightGain[i], g.dayGain[i]);
        }
        boolean pulse = g.pulse && !devPulseOff;
        double seconds = (ticks + partialTick) / 20.0;
        double wrap = g.pulsePeriod * 50.0;
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        s.safeGetUniform("OvHeatGlowA").set(codesOn ? 1.0F : 0.0F, (float) (seconds % wrap), g.pulsePeriod, g.pulseCell);
        s.safeGetUniform("OvHeatGlowB").set(gain[0], gain[1], gain[2], gain[3]);
        s.safeGetUniform("OvHeatGlowC").set(gain[4], g.skyBodyDayLift * day, 0.0F, 0.0F);
        float[] d = g.pulseDepth;
        float on = pulse ? 1.0F : 0.0F;
        s.safeGetUniform("OvHeatPulseA").set(d[0] * on, d[1] * on, d[2] * on, d[3] * on);
        s.safeGetUniform("OvHeatPulseB").set(d[4] * on, 0.0F, 0.0F, 0.0F);
        s.safeGetUniform("OvHeatSkyTint").set(Mth.lerp(dusk, 1.0F, g.duskTint[0]), Mth.lerp(dusk, 1.0F, g.duskTint[1]),
                Mth.lerp(dusk, 1.0F, g.duskTint[2]), 0.0F);
        s.safeGetUniform("OvHeatCam").set((float) (((cam.x % 1024.0) + 1024.0) % 1024.0), (float) (((cam.y % 1024.0) + 1024.0) % 1024.0),
                (float) (((cam.z % 1024.0) + 1024.0) % 1024.0), 0.0F);
    }

    static String describe() {
        return "目印 " + (codesOn ? "on" : "off") + (devCodesOff ? "(止め)" : "") + " 脈動 " + (devPulseOff ? "止め" : DesignData.heatFx().glow().pulse ? "on" : "off")
                + " " + HeatFxSky.describe();
    }
}
