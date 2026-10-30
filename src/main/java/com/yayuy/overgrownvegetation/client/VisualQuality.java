package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.config.AtmosphereQuality;
import com.yayuy.overgrownvegetation.config.CurledFernDetail;
import com.yayuy.overgrownvegetation.config.OvergrownClientConfig;

/**
 * クライアント設定の見た目の項目を、描く側が読む口(2026-10-23)。2026-10-30 夜に粒・光の筋・霧の板・雲の遠景・動く光・G1 の動きと
 * 品質の段階(QualityPreset)を画面の演出と一緒に parked/ へ退避し、巻きシダだけが残る(元の丸ごとは parked の同じパス)。値はクライアント設定
 * ({@link OvergrownClientConfig})にあり、設定がまだ読まれていない間(起動直後・チャンクを組み立てる別スレッドの最初など)は既定値。
 */
public final class VisualQuality {

    private static boolean loaded() {
        return OvergrownClientConfig.SPEC.isLoaded();
    }

    /** 巻きシダの精細さ。 */
    public static CurledFernDetail curledFern() {
        return loaded() ? OvergrownClientConfig.CLIENT.curledFernDetail.get() : OvergrownClientConfig.CLIENT.curledFernDetail.getDefault();
    }

    /** 過繁茂の密林の雰囲気を描くか(2026-10-30)。 */
    public static boolean atmosphere() {
        return loaded() ? OvergrownClientConfig.CLIENT.atmosphereEnabled.get() : OvergrownClientConfig.CLIENT.atmosphereEnabled.getDefault();
    }

    private static AtmosphereQuality quality() {
        return loaded() ? OvergrownClientConfig.CLIENT.atmosphereQuality.get() : OvergrownClientConfig.CLIENT.atmosphereQuality.getDefault();
    }

    /** 光る胞子の粒の量の倍率(0.5〜1)。 */
    public static float particles() {
        return quality().particles / 100.0F;
    }

    /** 上限 {@code max} に粒の量の倍率を掛けた数(1 以上)。 */
    public static int particleCap(int max) {
        return Math.max(1, Math.round(max * particles()));
    }

    /** 上限 {@code max} に光の筋の倍率を掛けた数(1 以上)。光の筋と木漏れ日のマスに使う。 */
    public static int shaftCap(int max) {
        return Math.max(1, Math.round(max * quality().shafts / 100.0F));
    }

    /** 重さの段の番号(0 = 高、1 = 中、2 = 低)。段ごとに数を決めてある演出(環境の胞子)が使う。 */
    public static int atmosphereTier() {
        return switch (quality()) {
            case HIGH -> 0;
            case MEDIUM -> 1;
            case LOW -> 2;
        };
    }

    /** Iris のシェーダーパック使用中もこの MOD の演出を描くか。 */
    public static boolean effectsWithShaderPack() {
        return loaded() ? OvergrownClientConfig.CLIENT.effectsWithShaderPack.get() : false;
    }

    private VisualQuality() {
    }
}
