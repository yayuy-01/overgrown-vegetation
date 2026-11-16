package com.yayuy.overgrownvegetation.worldevent;

import org.jetbrains.annotations.Nullable;

/**
 * 0.2.0 のワールドイベント 7 つ(2026-11-16 ユーザー決定。正本は Vault「ワールドイベント 7 つの中身の案(Fable・11-16)」の【決定】)。
 * サーバー・クライアントの両方で使う。{@link #id} はコマンド・design json・見た目の側の目印で、<b>変えない</b>(パケットは {@link #ordinal()} を送るので、
 * 並びを変えるときはネットワークの版も上げる)。
 *
 * <p>植物に関わる 2 つ(胞子のオーロラ・光る潮)はフェーズ F3 から、ほかは最初から(ユーザー)。効き目があるのは地脈の大潮だけ(汲み上げ器が少し速くなる【仮】)。
 */
public enum WorldEventKind {
    /** 胞子のオーロラ(夜)。F3 から。 */
    AURORA("aurora", "胞子のオーロラ"),
    /** 光る潮(夜の海・川・湖が青緑に光る)。F3 から。 */
    GLOWING_TIDE("glowing_tide", "光る潮"),
    /** 日食(昼・約 6 分、真っ暗なのは約 1.5 分。夜と同じ暗さ)。見た目だけ。 */
    ECLIPSE("eclipse", "日食"),
    /** 地脈の大潮(半日)。汲み上げ器が少し速くなる。 */
    LEY_TIDE("ley_tide", "地脈の大潮"),
    /** 日暈(昼の数時間)。 */
    HALO("halo", "日暈"),
    /** 流星群(夜)。火球に音は付けない。 */
    METEOR_SHOWER("meteor_shower", "流星群"),
    /** 地平線を歩く影(朝か夕の約 10 分。霞か霧のある日だけ。監視塔に似せる)。 */
    HORIZON_SHADOW("horizon_shadow", "地平線を歩く影");

    public final String id;
    public final String jaName;

    WorldEventKind(String id, String jaName) {
        this.id = id;
        this.jaName = jaName;
    }

    private static final WorldEventKind[] VALUES = values();

    @Nullable
    public static WorldEventKind byId(String id) {
        for (WorldEventKind k : VALUES) {
            if (k.id.equals(id)) return k;
        }
        return null;
    }

    /** パケットの番号から(範囲外・負は null = 何も起きていない)。 */
    @Nullable
    public static WorldEventKind byIndex(int i) {
        return i >= 0 && i < VALUES.length ? VALUES[i] : null;
    }
}
