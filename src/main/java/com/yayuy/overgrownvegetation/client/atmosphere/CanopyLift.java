package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.util.Mth;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * <b>林床の持ち上げ</b>(一覧 5。2026-10-17 ユーザー「木の密度を少しだけ上げて、その状態で木漏れ日や差し込む光が美しく見えれば完璧」、
 * 2026-10-30 作り直し。元は parked の {@code CanopyLight#skyLift}・{@code liftedSkyBrightness})。
 *
 * <p>林床はバニラの空の光の減り(葉 1 枚ごとに 1 段)で暗くなり、濃い森・巨大樹の下は真っ暗に沈む。影の暗さは林冠の影
 * ({@link CanopyShade})が受け持つので、空の光の減りの方を {@code skyLift}(0.6)の割合だけ戻す。読み手は
 * {@code LightTextureCanopyLiftMixin}(ライトマップ = 地形・Mob・粒・アイテムが同じ明るさ、Sodium でも効く)。見た目だけで、
 * サーバーの明るさ・湧き・作物と葉のブロックの光の減り方は変えない。強さは森の中で空の下にいる度合い({@link ForestPresence#openForest})。
 */
public final class CanopyLift {

    /** 今の持ち上げの強さ(0〜1)。 */
    public static float skyLift() {
        if (!VisualQuality.canopyLight()) {
            return 0.0F;
        }
        return DesignData.atmosphere().skyLift() * ForestPresence.openForest();
    }

    /**
     * 空の光の段 {@code level}(0〜15)の明るさを、15 との差の {@code lift} の割合だけ明るい方へ寄せて返す(バニラの {@code LightTexture#getBrightness} と
     * 同じ曲線を小数の段で)。空の光がほとんど届かない所(0〜3 段)は寄せ方を {@code skyLiftDark}(0.45)倍までに抑え、7 段から全部寄せる
     * (0 段は持ち上げ 0.6 でバニラの 4 段ほど = 薄暗い。2026-10-17、間隔 5・空き地の少ない森では林床の多くが 0〜1 段だった)。
     */
    public static float liftedSkyBrightness(DimensionType dimension, int level, float lift) {
        float gate = Mth.lerp(SunLight.smooth(Mth.clamp(level / 7.0F, 0.0F, 1.0F)), DesignData.atmosphere().skyLiftDark(), 1.0F);
        float f = (level + lift * gate * (15 - level)) / 15.0F;
        return Mth.lerp(dimension.ambientLight(), f / (4.0F - 3.0F * f), 1.0F);
    }

    /**
     * 緑の照り返し(A3、10-30 夜 ユーザー採用。参考画像「影は深いが緑を帯びる」): 森の中では、ライトマップの 1 画素(ABGR)の中ほどの明るさに
     * 緑を少し足す(明るさ L の 4L(1 − L) で、真っ暗と真っ白には足さない)。強さは {@code light.greenBounce}(仮 0.06)× 森の中で空の下にいる度合い。
     * 見た目だけ(サーバーの明るさ・湧きは変わらない)。読み手は {@code LightTextureCanopyLiftMixin}。
     */
    public static int greenBounce(int abgr) {
        if (!VisualQuality.canopyLight()) {
            return abgr;
        }
        float k = DesignData.atmosphere().greenBounce() * ForestPresence.openForest();
        if (k <= 0.001F) {
            return abgr;
        }
        int r = abgr & 255;
        int g = abgr >> 8 & 255;
        int b = abgr >> 16 & 255;
        float l = (0.299F * r + 0.587F * g + 0.114F * b) / 255.0F;
        float w = 4.0F * l * (1.0F - l) * k * 255.0F;
        r = Mth.clamp(Math.round(r - 0.4F * w), 0, 255);
        g = Mth.clamp(Math.round(g + w), 0, 255);
        b = Mth.clamp(Math.round(b - 0.4F * w), 0, 255);
        return abgr & 0xFF000000 | b << 16 | g << 8 | r;
    }

    private CanopyLift() {
    }
}
