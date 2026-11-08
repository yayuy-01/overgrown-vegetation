package com.yayuy.overgrownvegetation.sky;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SkyTable;

/**
 * 雲の形を決める設計値(2026-11-08 雲の段階 1 の仕上げ 8)。並び方・量の固定・雲の海の厚さ・平らさ。
 *
 * <p>前はサーバーとクライアントがそれぞれ自分の {@code sky.json} の写しを読んでいたので、マルチプレイで写しが違うと雲の形が食い違った。
 * 今はサーバーの値を種と一緒に送り({@code CloudDriftPayload})、クライアントは届いた値を使う({@link #setOverride})。届く前・この MOD の無い
 * サーバーでは自分の設計値。見た目だけの値(色・影の強さなど)は送らない(各自の好みでよい)。
 */
public record CloudShape(int style, float coverage, float floorThickness, float floorFlatness) {

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
            c = new CloudShape(sky.style, (float) sky.coverage, sky.floorThickness, sky.floorFlatness);
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
