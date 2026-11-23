package com.yayuy.overgrownvegetation.ecosystem;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 生態系の台帳の設計値({@code design/ecosystem.json}、2026-11-16)。正本は Vault「生態系の台帳(兵站・バイオマス)」。
 * 数はどれも【仮】(全体管理 11-16 了承: 升 4×4 チャンク・満員獲物の環境生物 12・狩る側の環境生物 4・5 日で戻る)。
 * 2026-11-23 片付け E: 森の中の守り(inner)と仮の種(stub)の湧きの値は消した(湧きは前線と部隊の守りの部隊)。
 */
public final class EcoTable {

    // --- Gson DTO ---
    public static final class Json {
        public Boolean enabled;
        public Integer cellShift;
        public Integer fullPrey, fullHunter;
        public Double recoveryDays;
        public Double weightPrey, weightHunter, scale;
        public Double incomeFloor;
        /** 倒木の端を洞にする確率(11-18 ユーザー「あげていいよ」で 0.7)。 */
        public Double denChance;
        public List<String> countedDamageTypes;
        /** 行動の段(11-18): 種の id(名前空間なし)ごとの値。省いた値はコードの既定(EcoBehavior)。 */
        public java.util.Map<String, com.yayuy.overgrownvegetation.ecosystem.life.EcoBehavior.Json> behavior;
        /** 地図の上の生態系(2026-11-21): 升ごとのバニラの動物・村人の頭数と感染。 */
        public com.yayuy.overgrownvegetation.ecosystem.map.MapTable.Json map;
    }

    public final boolean enabled;
    /** 升の一辺 = 2^cellShift チャンク(既定 2 = 4×4 チャンク)。 */
    public final int cellShift;
    public final int fullPrey, fullHunter;
    public final double recoveryDays;
    /** 多様性 = 1 − Σ(欠け × 重み) ÷ scale(全体管理 11-16 決定 A: 固定の物差し)。 */
    public final double weightPrey, weightHunter, scale;
    /** 予算の貯まる速さの倍率 = floor + (1 − floor) × 多様性(決定 C)。 */
    public final double incomeFloor;
    /** 倒木の端を環境生物の洞にする確率(1 つの端ごと・座標の乱数)【仮 0.7】。 */
    public final double denChance;
    public final List<String> countedDamageTypes;

    /** 地図の上の生態系(2026-11-21)。 */
    public final com.yayuy.overgrownvegetation.ecosystem.map.MapTable map;

    public static final EcoTable DEFAULT = fromJson(null);

    private EcoTable(Json j) {
        enabled = j.enabled == null || j.enabled;
        cellShift = clamp(or(j.cellShift, 2), 0, 5);
        fullPrey = Math.max(1, or(j.fullPrey, 12));
        fullHunter = Math.max(1, or(j.fullHunter, 4));
        recoveryDays = Math.max(0.01D, or(j.recoveryDays, 5.0D));
        weightPrey = Math.max(0.0D, or(j.weightPrey, 1.0D));
        weightHunter = Math.max(0.0D, or(j.weightHunter, 2.5D));
        scale = Math.max(1.0E-6D, or(j.scale, 50.0D));
        incomeFloor = Math.max(0.0D, Math.min(1.0D, or(j.incomeFloor, 0.5D)));
        denChance = Math.max(0.0D, Math.min(1.0D, or(j.denChance, 0.7D)));
        countedDamageTypes = j.countedDamageTypes != null ? List.copyOf(j.countedDamageTypes)
                : List.of("overgrown_vegetation:autocannon", "overgrown_vegetation:barbed_wire");
        map = com.yayuy.overgrownvegetation.ecosystem.map.MapTable.fromJson(j.map);
        com.yayuy.overgrownvegetation.ecosystem.life.EcoBehavior.load(j.behavior);
    }

    public static EcoTable fromJson(@Nullable Json j) {
        return new EcoTable(j != null ? j : new Json());
    }

    /** 升の一辺のチャンク数。 */
    public int cellChunks() {
        return 1 << cellShift;
    }

    public int full(int kind) {
        return kind == Ecosystem.HUNTER ? fullHunter : fullPrey;
    }

    public double weight(int kind) {
        return kind == Ecosystem.HUNTER ? weightHunter : weightPrey;
    }

    private static int or(@Nullable Integer v, int d) {
        return v != null ? v : d;
    }

    private static double or(@Nullable Double v, double d) {
        return v != null && Double.isFinite(v) ? v : d;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
