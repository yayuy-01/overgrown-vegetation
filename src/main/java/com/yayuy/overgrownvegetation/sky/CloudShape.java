package com.yayuy.overgrownvegetation.sky;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SkyTable;

/**
 * 雲の形を決める設計値(2026-11-08 雲の段階 1 の仕上げ 8)。並び方・量の固定・雲の海の厚さ・平らさ。
 * 2026-11-09 雲の段階 2a で積乱雲の値を足した: storms = 積乱雲を立てるか(0 / 1)・stormChance = 1 升に立つ確率の固定(−1 = 日の雲の量で)・
 * giantChance = 立つ雲のうち巨大になる割合(−1 = 既定)・footScale = 積乱雲の足元の裾野の高さの倍率。
 * 2026-11-09 段階 2a-5 で天気の値を足した: syncRain = バニラの雨を雲の多い日に合わせるか(0 / 1)・rainDays = 多い日のうち雨の日の割合・
 * thunderShare = 雨のうち雷雨の割合・preRainTicks = 雨の前にだんだん曇る長さ(tick)。
 *
 * <p>前はサーバーとクライアントがそれぞれ自分の {@code sky.json} の写しを読んでいたので、マルチプレイで写しが違うと雲の形が食い違った。
 * 今はサーバーの値を種と一緒に送り({@code CloudDriftPayload})、クライアントは届いた値を使う({@link #setOverride})。届く前・この MOD の無い
 * サーバーでは自分の設計値。見た目だけの値(色・影の強さ・巻雲など)は送らない(各自の好みでよい)。
 */
public record CloudShape(int style, float coverage, float floorThickness, float floorFlatness,
                         float storms, float stormChance, float giantChance, float footScale,
                         float syncRain, float rainDays, float thunderShare, float preRainTicks) {

    /** 天気の値は既定のまま(11-09 段階 2a-5 の前の作り)。 */
    public CloudShape(int style, float coverage, float floorThickness, float floorFlatness, float storms, float stormChance, float giantChance, float footScale) {
        this(style, coverage, floorThickness, floorFlatness, storms, stormChance, giantChance, footScale, 1.0F, 0.75F, 0.3F, 4000.0F);
    }

    /** 積乱雲の値は既定のまま(段階 1 の GameTest など)。 */
    public CloudShape(int style, float coverage, float floorThickness, float floorFlatness) {
        this(style, coverage, floorThickness, floorFlatness, 1.0F, -1.0F, -1.0F, 1.0F, 1.0F, 0.75F, 0.3F, 4000.0F);
    }

    private static volatile CloudShape override;

    /** 今の値(届いた値があればそれ、無ければ自分の設計値)。 */
    public static CloudShape current() {
        CloudShape o = override;
        return o != null ? o : fromDesign();
    }

    private static volatile SkyTable cachedTable;
    private static volatile CloudShape cached;

    /** 自分の設計値(雲の柱を引くたびに呼ばれるので、設計値の表ごとに 1 つ作って覚える)。 */
    public static CloudShape fromDesign() {
        SkyTable sky = DesignData.sky();
        CloudShape c = cached;
        if (c == null || cachedTable != sky) {
            c = new CloudShape(sky.style, (float) sky.coverage, sky.floorThickness, sky.floorFlatness,
                    sky.storms, sky.stormChance, sky.giantChance, sky.footScale, sky.syncRain, sky.rainDays, sky.thunderShare, sky.preRainTicks);
            cached = c;
            cachedTable = sky;
        }
        return c;
    }

    /** サーバーから届いた値(null で外す)。 */
    public static void setOverride(CloudShape shape) {
        override = shape;
    }
}
