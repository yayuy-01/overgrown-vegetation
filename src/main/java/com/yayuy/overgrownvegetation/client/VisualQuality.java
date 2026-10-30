package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.config.CurledFernDetail;
import com.yayuy.overgrownvegetation.config.OvergrownClientConfig;
import com.yayuy.overgrownvegetation.config.QualityPreset;

/**
 * 品質の段階({@link QualityPreset})で変わる重さの項目を、描く側が読む口(2026-10-23)。値はクライアント設定
 * ({@link OvergrownClientConfig})にあり、設定がまだ読まれていない間(起動直後・チャンクを組み立てる別スレッドの最初など)は既定値 = 高。
 * どれも毎回読むだけなので、画面で「完了」を押せばすぐ効く(霧の板・雲の遠景は次の作り直しで。どちらも値の変化を見て作り直す)。
 */
public final class VisualQuality {

    private static boolean loaded() {
        return OvergrownClientConfig.SPEC.isLoaded();
    }

    /** 粒の量(0.1〜1)。 */
    public static float particles() {
        return loaded() ? OvergrownClientConfig.CLIENT.particleAmount.get() / 100.0F : 1.0F;
    }

    /** 光の筋の本数の倍率(0.1〜1)。 */
    public static float shafts() {
        return loaded() ? OvergrownClientConfig.CLIENT.lightShafts.get() / 100.0F : 1.0F;
    }

    /** 上限 {@code max} に粒の量を掛けた数(1 以上)。 */
    public static int particleCap(int max) {
        return Math.max(1, Math.round(max * particles()));
    }

    /** 上限 {@code max} に光の筋の倍率を掛けた数(1 以上)。 */
    public static int shaftCap(int max) {
        return Math.max(1, Math.round(max * shafts()));
    }

    /** 霧の板を減らすか。 */
    public static boolean fewMistSheets() {
        return loaded() && OvergrownClientConfig.CLIENT.mistSheets.get() == QualityPreset.MistSheets.FEW;
    }

    /** 雲の遠景を粗くするか。 */
    public static boolean coarseFarClouds() {
        return loaded() && OvergrownClientConfig.CLIENT.farClouds.get() == QualityPreset.FarCloudDetail.COARSE;
    }

    /** 動く光を描くか。 */
    public static boolean dynamicLights() {
        return !loaded() || OvergrownClientConfig.CLIENT.dynamicLights.get();
    }

    /** G1 の進化体の手続きの動き(胞子嚢の鼓動・触手の揺れ)を足すか。 */
    public static boolean organicMotion() {
        return !loaded() || OvergrownClientConfig.CLIENT.organicMotion.get();
    }

    /** 巻きシダの精細さ。 */
    public static CurledFernDetail curledFern() {
        return loaded() ? OvergrownClientConfig.CLIENT.curledFernDetail.get() : OvergrownClientConfig.CLIENT.curledFernDetail.getDefault();
    }

    private VisualQuality() {
    }
}
