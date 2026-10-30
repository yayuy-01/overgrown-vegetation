package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * 高空の晴天(2026-10-17。ユーザー指定「どんな過繁茂したバイオームであっても高度 300〜350 辺りから神秘的なレベルで奇麗な晴天になるように。
 * 空の巨大生物とのバトルフィールド・空を移動する際の目玉となる場所なので、美しい場所を用意したい」)。
 *
 * <p>カメラの高さが {@code highSkyClearFrom}(300)から {@code highSkyClearTo}(350)へ上がるにつれて {@link #clarity()} が 0 → 1。これが 1 に近いほど、
 * 過繁茂・極相の空の演出を外す:
 * <ul>
 *   <li>森の霧の距離・霧の色の下限・太陽の側の霧の明るさ・二色の霧・森の中の青緑({@link CorruptionAtmosphere#forestFactor} と霧の処理)</li>
 *   <li>極相の昼の暗さ({@link CorruptionAtmosphere#climaxSkyDarken})と極相の演出({@link CorruptionAtmosphere#jungleClimax})</li>
 *   <li>バイオームの空の色・霧の色(過繁茂は灰緑、極相の湿地は黒に近い)をバニラの晴れた空の色へ({@code mixin/client/BiomeClearSkyMixin})</li>
 *   <li>漂う胞子(バイオームの粒)を出さない({@link DriftingSporeParticle.Provider})</li>
 * </ul>
 * 霧の距離はバニラ(描画距離の端)まで戻るので、雲の海と山が遠くまで見える。
 */
public final class HighSky {

    /** 0(地上の空)〜 1(高空の晴天)。 */
    public static float clarity() {
        double setting = OvergrownVegetationConfig.COMMON.highSkyClarity.get();
        if (setting <= 0.0) {
            return 0.0F;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.gameRenderer == null) {
            return 0.0F;
        }
        double y = mc.gameRenderer.getMainCamera().getPosition().y;
        float from = OvergrownVegetationConfig.COMMON.highSkyClearFrom.get().floatValue();
        float to = Math.max(from + 1.0F, OvergrownVegetationConfig.COMMON.highSkyClearTo.get().floatValue());
        return (float) setting * CanopyLight.smooth(Mth.clamp((float) ((y - from) / (to - from)), 0.0F, 1.0F));
    }

    /** 晴天の始まる高さ(胞子を止める高さ)。 */
    public static float from() {
        return OvergrownVegetationConfig.COMMON.highSkyClearFrom.get().floatValue();
    }

    /** 高空の晴天の空の色・霧の色(バニラの平原の晴れた空)。 */
    public static final int CLEAR_SKY = 0x78A7FF;
    public static final int CLEAR_FOG = 0xC0D8FF;
    /**
     * 高く上がるほど空が濃くなる(2026-10-18 ユーザー選択「高く上がるほど空の色が濃くなる」。参考画像の上の方ほど濃い群青): {@link #altitude()} が 1 のときの空の色・霧の色。
     * 空は {@value #DEEP_SKY_MIX}、霧(地平の色)は {@value #DEEP_FOG_MIX} の割合まで寄せる。色・割合は仮決め。
     */
    public static final int DEEP_SKY = 0x2C55D8;
    public static final int DEEP_FOG = 0x6E95E0;
    public static final float DEEP_SKY_MIX = 0.85F;
    public static final float DEEP_FOG_MIX = 0.75F;
    /** 濃くなり始める高さと、いちばん濃くなる高さ(巨大な積乱雲の天辺より少し上)。 */
    public static final double DEEP_FROM = 450.0;
    public static final double DEEP_TO = 3200.0;

    /** 高さで空が濃くなる度合い(0〜1。設定 skyLight を掛ける)。 */
    public static float altitude() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.gameRenderer == null) {
            return 0.0F;
        }
        double y = mc.gameRenderer.getMainCamera().getPosition().y;
        float a = CanopyLight.smooth(Mth.clamp((float) ((y - DEEP_FROM) / (DEEP_TO - DEEP_FROM)), 0.0F, 1.0F));
        return a * com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON.skyLight.get().floatValue();
    }

    /** 高空の晴天の空の色(高さで濃く)。 */
    public static int clearSky() {
        return lerpColor(altitude() * DEEP_SKY_MIX, CLEAR_SKY, DEEP_SKY);
    }

    /** 高空の晴天の霧の色(地平の色。高さで少し濃く = 雲の上の遠くの霞が白く浮かない)。 */
    public static int clearFog() {
        return lerpColor(altitude() * DEEP_FOG_MIX, CLEAR_FOG, DEEP_FOG);
    }

    /** 色(0xRRGGBB)を a の割合で b へ寄せる。 */
    public static int lerpColor(float a, int from, int to) {
        int r = Math.round(Mth.lerp(a, (from >> 16) & 255, (to >> 16) & 255));
        int g = Math.round(Mth.lerp(a, (from >> 8) & 255, (to >> 8) & 255));
        int b = Math.round(Mth.lerp(a, from & 255, to & 255));
        return (r << 16) | (g << 8) | b;
    }

    private HighSky() {
    }
}
