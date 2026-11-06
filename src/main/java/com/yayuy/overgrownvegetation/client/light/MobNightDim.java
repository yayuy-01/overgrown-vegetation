package com.yayuy.overgrownvegetation.client.light;

import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.client.atmosphere.CanopyLift;
import com.yayuy.overgrownvegetation.client.atmosphere.ForestPresence;
import com.yayuy.overgrownvegetation.client.atmosphere.SunLight;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * <b>夜の森の Mob を沈める</b>(2026-11-06、ユーザー「やりましょう」= 沈める方向。値は仮決め)。
 *
 * <p>夜の過繁茂の森では、地形は MOD のシェーダーで暗くなる(月の向きの面の明暗・月の影・暗い所の彩度落とし。{@code ov_light.glsl})。
 * Mob・手に持った物・落ちている物はライトマップと霧しか受けないので、周りの 2〜2.6 倍の明るさの灰白の人影に浮いていた
 * (光って見え、見えすぎて怖さが消える)。そこで Mob の明るさの<b>空の光の段だけ</b>を、明るさが {@code mobNight} 倍になる段まで下げる
 * (段 s の明るさは林床の持ち上げ込みの {@link CanopyLift#liftedSkyBrightness} で比べる)。
 *
 * <ul>
 *     <li>過繁茂の森の中だけ({@link ForestPresence#overgrownShare})。過繁茂の外の夜(村など)はバニラのまま。地下(岩の天井の下)では効かない。</li>
 *     <li>夜だけ({@link SunLight#night}。夕方 12500 は 0)。強さは月の満ち欠けで {@code mobNightNew}(新月)〜{@code mobNightFull}(満月)。</li>
 *     <li>ブロックの光の段(松明・洞窟の光 {@link DynamicLights})は触らない。灯りの近くの Mob は今の明るさのまま。</li>
 * </ul>
 * 読み手は {@code EntityRendererNightDimMixin}(Sodium の有無に関係なく、すべての Mob の描き方が通る {@code getPackedLightCoords})。見た目だけ。
 */
public final class MobNightDim {

    private static final int[] REMAP = new int[16];
    private static float cachedLift = Float.NaN;
    private static float cachedDim = Float.NaN;
    private static long cachedFrame = Long.MIN_VALUE;
    private static float cachedFactor = 1.0F;

    private MobNightDim() {
    }

    /** 今の Mob の明るさの倍率(1 = そのまま)。フレームの中では同じ値を使う。 */
    public static float factor() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !VisualQuality.canopyLight()) {
            return 1.0F;
        }
        long frame = level.getGameTime() * 4L + (long) (mc.getTimer().getGameTimeDeltaPartialTick(false) * 4.0F);
        if (frame == cachedFrame) {
            return cachedFactor;
        }
        cachedFrame = frame;
        var t = DesignData.atmosphere();
        float k = SunLight.night(level, 1.0F) * ForestPresence.overgrownShare() * (1.0F - ForestPresence.underground());
        float dim = Mth.lerp(level.getMoonBrightness(), t.mobNightNew(), t.mobNightFull());
        cachedFactor = Mth.lerp(k, 1.0F, dim);
        return cachedFactor;
    }

    /** 詰めた光の値({@code block << 4 | sky << 20})の空の光の段を、明るさが {@link #factor()} 倍になる段へ下げる。 */
    public static int dim(int packed) {
        float f = factor();
        if (f >= 0.999F) {
            return packed;
        }
        int sky = LightTexture.sky(packed);
        if (sky <= 0) {
            return packed;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return packed;
        }
        float lift = CanopyLift.skyLift();
        if (lift != cachedLift || f != cachedDim) {
            rebuild(level.dimensionType(), lift, f);
        }
        return (packed & ~(0xF << 20)) | (REMAP[sky] << 20);
    }

    private static void rebuild(DimensionType dimension, float lift, float f) {
        cachedLift = lift;
        cachedDim = f;
        float[] b = new float[16];
        for (int s = 0; s < 16; s++) {
            b[s] = lift > 0.001F ? CanopyLift.liftedSkyBrightness(dimension, s, lift) : LightTexture.getBrightness(dimension, s);
        }
        for (int s = 0; s < 16; s++) {
            float target = b[s] * f;
            int best = s;
            float bestErr = Float.MAX_VALUE;
            for (int c = 0; c <= s; c++) {
                float err = Math.abs(b[c] - target);
                if (err < bestErr) {
                    bestErr = err;
                    best = c;
                }
            }
            REMAP[s] = best;
        }
    }
}
