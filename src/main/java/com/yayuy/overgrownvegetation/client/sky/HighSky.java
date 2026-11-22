package com.yayuy.overgrownvegetation.client.sky;

import com.yayuy.overgrownvegetation.client.atmosphere.SunLight;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SkyTable;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * 高空の晴天(2026-10-17。ユーザー指定「どんな過繁茂したバイオームであっても高度 300〜350 辺りから神秘的なレベルで奇麗な晴天になるように」。
 * 2026-11-08 雲の段階 1 で parked から戻した)。カメラの高さが {@code sky.json highSky.clearFrom}(300)から {@code clearTo}(350)へ上がるにつれて
 * {@link #clarity()} が 0 → 1。これが効く所:
 * <ul>
 *   <li>雲の上では雨・雪を降らせない({@code mixin/client/LevelRainAboveCloudsMixin}。雨の暗さ・灰色の空も消える)</li>
 *   <li>バイオームの空の色・霧の色をバニラの晴れた空の色へ、高く上がるほど濃い群青へ({@code mixin/client/BiomeClearSkyMixin})</li>
 * </ul>
 * 前の版が外していた森の霧・極相の暗さは、今の霧({@code client.atmosphere.MistView})が高さで自分で消えるので、ここでは触らない。
 * 雲を切っている(設定・シェーダーパック)ときは何もしない。色・割合は仮決め。
 */
public final class HighSky {

    private static volatile float clarityCache;
    private static volatile float altitudeCache;

    /** 0(地上の空)〜 1(高空の晴天)。11-08 改善 ⑲: フレームの初めに求めた値({@link #refresh})。Mixin から 1 フレームに何百回も呼ばれる。 */
    public static float clarity() {
        return clarityCache;
    }

    /** フレームの初めに 1 回({@link VoxelClouds#onRenderFrame} から)。 */
    static void refresh() {
        clarityCache = computeClarity();
        altitudeCache = computeAltitude();
    }

    private static float computeClarity() {
        SkyTable sky = DesignData.sky();
        if (sky.highSkyClarity <= 0.0F || !VoxelClouds.isActive()) {
            return 0.0F;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.gameRenderer == null || !mc.level.dimensionType().hasSkyLight()) {
            return 0.0F;
        }
        double y = mc.gameRenderer.getMainCamera().getPosition().y;
        return sky.highSkyClarity * SunLight.smooth(Mth.clamp((float) ((y - sky.highSkyClearFrom) / (sky.highSkyClearTo - sky.highSkyClearFrom)), 0.0F, 1.0F));
    }

    /** 高空の晴天の空の色・霧の色(バニラの平原の晴れた空)。 */
    public static final int CLEAR_SKY = 0x78A7FF;
    public static final int CLEAR_FOG = 0xC0D8FF;
    /** 高く上がるほど空が濃くなる(2026-10-18 ユーザー選択): いちばん濃いときの色と寄せる割合。仮決め。 */
    public static final int DEEP_SKY = 0x2C55D8;
    public static final int DEEP_FOG = 0x6E95E0;
    public static final float DEEP_SKY_MIX = 0.85F;
    public static final float DEEP_FOG_MIX = 0.75F;
    public static final double DEEP_FROM = 450.0;
    public static final double DEEP_TO = 3200.0;

    /** 高さで空が濃くなる度合い(0〜1。フレームの初めに求めた値)。 */
    public static float altitude() {
        return altitudeCache;
    }

    private static float computeAltitude() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.gameRenderer == null) {
            return 0.0F;
        }
        double y = mc.gameRenderer.getMainCamera().getPosition().y;
        return SunLight.smooth(Mth.clamp((float) ((y - DEEP_FROM) / (DEEP_TO - DEEP_FROM)), 0.0F, 1.0F));
    }

    public static int clearSky() {
        return lerpColor(altitude() * DEEP_SKY_MIX, CLEAR_SKY, DEEP_SKY);
    }

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
