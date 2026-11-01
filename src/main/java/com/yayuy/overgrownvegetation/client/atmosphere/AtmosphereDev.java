package com.yayuy.overgrownvegetation.client.atmosphere;

/**
 * 開発用の口(Claude のテストクライアントの DevBridge から。撮り比べのためにこのセッションだけ値を替える)。ふだんの遊びでは使わない。
 */
public final class AtmosphereDev {

    /** 環境の胞子の梢より上の出し方({@link AmbientSpores#ceilingMode})。 */
    public static void sporeCeilingMode(int mode) {
        AmbientSpores.ceilingMode = Math.max(0, Math.min(3, mode));
    }

    /** 環境の胞子が日向で明るくなる割合。負で設計値に戻す。 */
    public static void sporeLit(float boost) {
        AmbientSpores.litOverride = boost;
    }

    /** 前線が過ぎたあとの粒の余韻(秒)。負で設計値に戻す。 */
    public static void afterglow(double seconds) {
        WindHistory.afterglowOverride = seconds;
    }

    /** 地下モードの点光源の数の上限(負で設計値。11-01 試作の重さの比べ)。 */
    public static void caveMaxLights(int max) {
        CaveLights.devMaxLights = max;
    }

    /** 地下モードの今の状態(1 行)。 */
    public static String caveStatus() {
        return CaveLights.describe();
    }

    private AtmosphereDev() {
    }
}
