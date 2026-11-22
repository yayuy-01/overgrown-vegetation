package com.yayuy.overgrownvegetation.ecosystem.map;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 地図の上の生態系の設計値({@code design/ecosystem.json} の {@code map} 節、2026-11-21)。正本は Vault「前線と部隊(地図の上の森の軍勢)(11-21)」。
 * 数はどれも【仮】(感染 1 回の稼ぎだけは司令塔の {@code EconomyTable#infectGain} を使う = 物差しは向こうに 1 か所)。
 */
public final class MapTable {

    // --- Gson DTO ---
    public static final class Json {
        public Boolean enabled;
        public Integer defaultFull, villageFull, barrenBelow;
        /** バイオームの id か {@code #タグ} → 升の満員の頭数(バニラの動物)。先に書いたものが勝つ。 */
        public Map<String, Integer> biomeFull;
        public Double infectPerMobPerSecond, germMinMul, lackRecoveryDays;
        public Integer syncIntervalTicks, reconcilePerSync, publishIntervalTicks, retryOvergrownTicks;
    }

    public final boolean enabled;
    /** 升の満員(動物の湧きの表があるバイオームで、{@link #biomeFull} に無いとき)。 */
    public final int defaultFull;
    /** 村のある升に足す村人の数。 */
    public final int villageFull;
    /** 満員がこれ未満の升は生き物の少ない升(この道を使わず、胞子だけで広がる)。 */
    public final int barrenBelow;
    public final Map<String, Integer> biomeFull;
    /** 広げる部隊の 1 体が 1 秒に感染させる頭数。 */
    public final double infectPerMobPerSecond;
    /** 全部感染した升の発芽のしきい値の倍率(感染の割合 f で 1 − (1 − これ) × f)。 */
    public final double germMinMul;
    /** 森の外で殺された動物が満員に戻る日数(最後に減った時から直線)。0 以下 = 戻らない。 */
    public final double lackRecoveryDays;
    /** プレイヤーのまわりの升を実物と照らし合わせる間(tick)。 */
    public final int syncIntervalTicks;
    /** 1 回の照らし合わせで 1 升に感染させる実物の上限(一度に全部倒れないように)。 */
    public final int reconcilePerSync;
    /** 胞子の場へ倍率を渡す間(tick。変わったときだけ)。 */
    public final int publishIntervalTicks;
    /** 過繁茂の印を断られた升を試し直す間(tick)。 */
    public final int retryOvergrownTicks;

    public static final MapTable DEFAULT = fromJson(null);

    private MapTable(Json j) {
        enabled = j.enabled == null || j.enabled;
        defaultFull = Math.max(0, or(j.defaultFull, 16));
        villageFull = Math.max(0, or(j.villageFull, 20));
        barrenBelow = Math.max(1, or(j.barrenBelow, 6));
        biomeFull = j.biomeFull != null ? java.util.Collections.unmodifiableMap(new LinkedHashMap<>(j.biomeFull)) : defaultBiomeFull();
        infectPerMobPerSecond = Math.max(0.0D, or(j.infectPerMobPerSecond, 1.0D / 30.0D));
        germMinMul = Math.max(0.01D, Math.min(1.0D, or(j.germMinMul, 0.3D)));
        lackRecoveryDays = or(j.lackRecoveryDays, 10.0D);
        syncIntervalTicks = Math.max(1, or(j.syncIntervalTicks, 100));
        reconcilePerSync = Math.max(0, or(j.reconcilePerSync, 3));
        publishIntervalTicks = Math.max(1, or(j.publishIntervalTicks, 100));
        retryOvergrownTicks = Math.max(1, or(j.retryOvergrownTicks, 1200));
    }

    /** 同梱の json と同じ既定(生き物の少ない土地 = 砂漠・海・高山・荒野・浜は満員を小さく)。 */
    private static Map<String, Integer> defaultBiomeFull() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("minecraft:plains", 24);
        m.put("minecraft:sunflower_plains", 24);
        m.put("minecraft:meadow", 20);
        m.put("minecraft:savanna", 20);
        m.put("minecraft:desert", 2);
        m.put("minecraft:jagged_peaks", 1);
        m.put("minecraft:frozen_peaks", 1);
        m.put("minecraft:stony_peaks", 0);
        m.put("minecraft:snowy_slopes", 2);
        m.put("minecraft:ice_spikes", 2);
        m.put("#minecraft:is_ocean", 0);
        m.put("#minecraft:is_beach", 0);
        m.put("#minecraft:is_badlands", 0);
        return java.util.Collections.unmodifiableMap(m);
    }

    public static MapTable fromJson(@Nullable Json j) {
        return new MapTable(j != null ? j : new Json());
    }

    /** 確かめ用の並び(同梱の json と既定値の比べ)。 */
    public String describe() {
        return enabled + "|" + defaultFull + "|" + villageFull + "|" + barrenBelow + "|" + biomeFull + "|" + infectPerMobPerSecond + "|" + germMinMul
                + "|" + lackRecoveryDays + "|" + syncIntervalTicks + "|" + reconcilePerSync + "|" + publishIntervalTicks + "|" + retryOvergrownTicks;
    }

    private static int or(@Nullable Integer v, int d) {
        return v != null ? v : d;
    }

    private static double or(@Nullable Double v, double d) {
        return v != null && Double.isFinite(v) ? v : d;
    }
}
