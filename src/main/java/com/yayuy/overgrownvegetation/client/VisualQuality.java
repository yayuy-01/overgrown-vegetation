package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.config.AtmosphereQuality;
import com.yayuy.overgrownvegetation.config.CurledFernDetail;
import com.yayuy.overgrownvegetation.config.OvergrownClientConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

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

    /**
     * この MOD の描画の演出をまとめて描くか(2026-11-23。偽 = 段の「なし」、{@link com.yayuy.overgrownvegetation.config.QualityPreset#NONE})。
     * 見た目だけの演出はどれもこれを見る(音は見ない)。
     */
    public static boolean effects() {
        return loaded() ? OvergrownClientConfig.CLIENT.visualEffects.get() : OvergrownClientConfig.CLIENT.visualEffects.getDefault();
    }

    /** 雰囲気の演出 1 つのオンオフ(2026-11-01)。親の {@link #atmosphere()} がオフなら、子はどれも偽。音はこれ。 */
    private static boolean part(ModConfigSpec.BooleanValue value) {
        return atmosphere() && (loaded() ? value.get() : value.getDefault());
    }

    /** 見た目の演出 1 つのオンオフ。{@link #part} に加えて、段の「なし」({@link #effects()} が偽)でも偽。 */
    private static boolean look(ModConfigSpec.BooleanValue value) {
        return effects() && part(value);
    }

    /** 虫・爬虫類の声。 */
    public static boolean ambientInsects() {
        return part(OvergrownClientConfig.CLIENT.ambientInsects);
    }

    /** 風の音。 */
    public static boolean ambientWind() {
        return part(OvergrownClientConfig.CLIENT.ambientWind);
    }

    /** 地下の音(滴・根の軋み・胞子の溜まりのうなり)。 */
    public static boolean ambientCave() {
        return part(OvergrownClientConfig.CLIENT.ambientCave);
    }

    /** 水中の音(こもった低音・バニラの水中の音を下げる)。 */
    public static boolean ambientUnderwater() {
        return part(OvergrownClientConfig.CLIENT.ambientUnderwater);
    }

    /** 光の筋とその中の粒。 */
    public static boolean lightShafts() {
        return look(OvergrownClientConfig.CLIENT.lightShafts);
    }

    /** 木漏れ日。 */
    public static boolean canopyDapple() {
        return look(OvergrownClientConfig.CLIENT.canopyDapple);
    }

    /** 林床の持ち上げ・緑の照り返し・林冠の影と色。 */
    public static boolean canopyLight() {
        return look(OvergrownClientConfig.CLIENT.canopyLight);
    }

    /** 胞子の霧・朝霧。 */
    public static boolean fog() {
        return look(OvergrownClientConfig.CLIENT.fog);
    }

    /** 霧の細部(レイマーチ)。霧がオフなら偽。 */
    public static boolean fogDetail() {
        return fog() && part(OvergrownClientConfig.CLIENT.fogDetail);
    }

    /** 落ち葉・光る胞子・夕暮れの粒・滴・湯気。 */
    public static boolean motes() {
        return look(OvergrownClientConfig.CLIENT.motes);
    }

    /** 羽虫・木の洞の虫。 */
    public static boolean critters() {
        return look(OvergrownClientConfig.CLIENT.critters);
    }

    /** 葉と草の揺れ。 */
    public static boolean leafSway() {
        return look(OvergrownClientConfig.CLIENT.leafSway);
    }

    /** 水面の輪と泡・濡れた照り(幹を伝う水は 2026-11-01 に退避)。 */
    public static boolean waterLife() {
        return look(OvergrownClientConfig.CLIENT.waterLife);
    }

    /** 水中の光・光る藻。 */
    public static boolean underwater() {
        return look(OvergrownClientConfig.CLIENT.underwater);
    }

    /** 地下モード(黒い霧・点光源・Mob の明るさ。11-01 試作)。 */
    public static boolean caveLight() {
        return look(OvergrownClientConfig.CLIENT.caveLight);
    }

    /** 光るもののにじみ(暗い所の光源の暈。2026-11-13 仕上げ B)。 */
    public static boolean glowBloom() {
        return look(OvergrownClientConfig.CLIENT.glowBloom);
    }

    /** テクスチャの明暗から面の凹凸(2026-11-14 仕上げ G。既定でオフ)。 */
    public static boolean surfaceBump() {
        return look(OvergrownClientConfig.CLIENT.surfaceBump);
    }

    /** 隙間と根元の陰り(簡易 AO。2026-11-14 仕上げ H。既定でオフ)。 */
    public static boolean simpleAo() {
        return look(OvergrownClientConfig.CLIENT.simpleAo);
    }

    /**
     * 雲(2026-11-08 雲の段階 1)。雰囲気の親のオンオフとは別(雲はバニラの雲の置き換えなので、雰囲気を切っても残す。仮決め)。
     * 重さの段は {@link #atmosphereTier()} を使う。
     */
    public static boolean clouds() {
        return effects() && (loaded() ? OvergrownClientConfig.CLIENT.clouds.get() : OvergrownClientConfig.CLIENT.clouds.getDefault());
    }

    /** Iris のシェーダーパック使用中もこの MOD の演出を描くか。 */
    public static boolean effectsWithShaderPack() {
        return loaded() ? OvergrownClientConfig.CLIENT.effectsWithShaderPack.get() : false;
    }

    private VisualQuality() {
    }
}
