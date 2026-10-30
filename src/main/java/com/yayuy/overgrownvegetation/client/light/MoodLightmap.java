package com.yayuy.overgrownvegetation.client.light;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Vector3f;

/**
 * ライトマップ(16×16 の表)の色味(2026-10-21 試作。入力は {@link MoodState})。
 *
 * <p>NeoForge の {@code DimensionSpecialEffects#adjustLightmapColors}(オーバーワールドは {@code OverworldCloudEffects})から、
 * 表の 1 マスごとに呼ばれる。ここはバニラが空の光とブロックの光を足した直後・暗視・暗闇・明るさ(ガンマ)の前なので、
 * 色を変えても明るさの設定とぶつからない。ライトマップなので地形・Mob・粒・手が同じ色になり、Sodium でも効く。
 *
 * <p>空の光の色だけを差し替える(明るさ = 輝度は保つ。暗くするのは極相の {@code LightTextureClimaxMixin}・林床の持ち上げは
 * {@code LightTextureCanopyLiftMixin} が受け持ち、どちらもここより前に済んでいる)。ブロックの光は森・極相・夜で少し暖かくする。
 * バニラの空の光の色 V = lerp((d, d, 1), 1, 0.35)(d = 空の暗さ)を作り直し、差だけを足すので、ボス戦の暗さなど他の手は残る。
 * 色は 2 回の見本でユーザーと決めた方向(過繁茂・極相は熱帯、10-22)。値そのものは仮決め。
 */
public final class MoodLightmap {

    /** 昼の汚染の外: ほんの少し暖かく。 */
    private static final Vector3f DAY_OUT = new Vector3f(1.03F, 1.0F, 0.94F);
    /** 夜: バニラの青一色をやめ、色の薄い灰青緑へ。 */
    private static final Vector3f NIGHT = new Vector3f(0.66F, 0.71F, 0.74F);
    /** ブロックの光(松明など)を暖かく寄せる色。 */
    private static final Vector3f BLOCK_WARM = new Vector3f(1.06F, 0.96F, 0.80F);

    // 過繁茂・極相は熱帯・蒸し暑い色味(2026-10-22 ユーザーが冷たい版と見比べて決定。冷たい版の値は DEV_NOTES と tools/lut/GenLut.java に残る)。
    /** 過繁茂の影(空の光の弱い段): 暖かい緑〜黄緑。 */
    private static final Vector3f FOREST_SHADE = new Vector3f(0.92F, 1.0F, 0.66F);
    /** 過繁茂の日なた(空の光 15 段): 白っぽい金。 */
    private static final Vector3f FOREST_SUN = new Vector3f(1.08F, 1.03F, 0.84F);
    /** 過繁茂の夜: 暖かい濃い緑。 */
    private static final Vector3f FOREST_NIGHT = new Vector3f(0.78F, 0.88F, 0.66F);
    /** 極相: 濃い緑〜褐色(青を入れない)。 */
    private static final Vector3f CLIMAX = new Vector3f(0.90F, 0.94F, 0.62F);
    /** 極相の日なた(林冠の穴から差す所): 強い白っぽい金(10-21 夜、ユーザー「極相の日向の金を強く」)。 */
    private static final Vector3f CLIMAX_SUN = new Vector3f(1.22F, 1.06F, 0.66F);
    /** 汚染地域の地下の暗がり: 濃い緑(地形のシェーダーの底上げ OvCave の色をこちらへ移した)。 */
    private static final Vector3f CAVE = new Vector3f(0.92F, 1.04F, 0.80F);

    public static void adjust(ClientLevel level, float skyDarken, float flicker, float skyLight, int blockIdx, int skyIdx, Vector3f colors) {
        if (!MoodState.lightmapActive()) {
            return;
        }
        float strength = MoodState.strength();
        float forest = MoodState.forest();
        float climax = MoodState.climax();
        float night = MoodState.night();
        float outside = 1.0F - forest - climax;

        // バニラの空の光の色。
        float vr = lerp(0.35F, skyDarken, 1.0F);
        float vb = 1.0F;
        float lumaV = luma(vr, vr, vb);

        // 空の光の段で影 ↔ 日なたを分ける(8 段から 15 段へ)。
        float sunny = smooth((skyIdx - 8) / 7.0F);
        Vector3f out = mixNorm(DAY_OUT, NIGHT, night);
        Vector3f forestDay = mixNorm(FOREST_SHADE, FOREST_SUN, sunny);
        Vector3f forestCol = mixNorm(forestDay, FOREST_NIGHT, night);
        // 極相の日なたは 10 段から金へ(林冠の穴から差す所が狭いので、過繁茂より手前の段から)。
        Vector3f climaxCol = mixNorm(CLIMAX, CLIMAX_SUN, smooth((skyIdx - 10) / 5.0F) * (1.0F - night));
        float tr = outside * out.x + forest * forestCol.x + climax * climaxCol.x;
        float tg = outside * out.y + forest * forestCol.y + climax * climaxCol.y;
        float tb = outside * out.z + forest * forestCol.z + climax * climaxCol.z;
        float scale = lumaV / Math.max(1.0E-4F, luma(tr, tg, tb));
        // 地下(洞窟・家の中)では空の光の色を変えない。
        float amount = 1.0F - MoodState.underground();
        float dr = (tr * scale - vr) * skyLight * amount;
        float dg = (tg * scale - vr) * skyLight * amount;
        float db = (tb * scale - vb) * skyLight * amount;

        // ブロックの光(バニラの式を作り直して、暖かく寄せた差だけ足す)。
        float warm = Math.max(Math.max(forest, climax), night) * 0.6F;
        if (warm > 0.001F) {
            float f9 = LightTexture.getBrightness(level.dimensionType(), blockIdx) * flicker;
            float f10 = f9 * ((f9 * 0.6F + 0.4F) * 0.6F + 0.4F);
            float f11 = f9 * (f9 * f9 * 0.6F + 0.4F);
            dr += f9 * (BLOCK_WARM.x - 1.0F) * warm;
            dg += f10 * (BLOCK_WARM.y - 1.0F) * warm;
            db += f11 * (BLOCK_WARM.z - 1.0F) * warm;
        }
        // バニラはここへ渡す前に 0.75 の灰色へ 4% 寄せている(足す差も同じ割合で縮める)。
        // 色味の強さ(設定、0〜1)。
        colors.add(dr * 0.96F * strength, dg * 0.96F * strength, db * 0.96F * strength);

        // 汚染地域の地下: 暗がり(松明の届かない所)を濃い緑へ(明るさは保つ。前は地形のシェーダーの OvCave が色ごと足していた)。
        Vector3f caveCol = CAVE;
        float cave = strength * MoodState.underground() * (forest + climax)
                * (1.0F - smooth(LightTexture.getBrightness(level.dimensionType(), blockIdx) * flicker / 0.6F));
        if (cave > 0.001F) {
            float l = luma(colors.x, colors.y, colors.z);
            float k = l / luma(caveCol.x, caveCol.y, caveCol.z);
            colors.set(lerp(cave, colors.x, caveCol.x * k), lerp(cave, colors.y, caveCol.y * k), lerp(cave, colors.z, caveCol.z * k));
        }
    }

    private static Vector3f mixNorm(Vector3f a, Vector3f b, float t) {
        return new Vector3f(lerp(t, a.x, b.x), lerp(t, a.y, b.y), lerp(t, a.z, b.z));
    }

    private static float luma(float r, float g, float b) {
        return 0.299F * r + 0.587F * g + 0.114F * b;
    }

    private static float lerp(float t, float a, float b) {
        return a + (b - a) * t;
    }

    private static float smooth(float t) {
        t = Math.max(0.0F, Math.min(1.0F, t));
        return t * t * (3.0F - 2.0F * t);
    }

    private MoodLightmap() {
    }
}
