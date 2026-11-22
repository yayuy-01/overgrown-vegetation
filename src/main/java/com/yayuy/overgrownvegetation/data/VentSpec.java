package com.yayuy.overgrownvegetation.data;

/**
 * 換気の機械とフィルターの設計値({@code spore_field.json} の {@code vent}、2026-11-14 サバイバルの背骨 段 2c)。仕様の正本は Vault「サバイバルの背骨(籠城と遠征)」の決定 9・11・15・16・18・27。
 * 読むのは {@code survival/vent/}(換気の機械・フィルター)と {@code spore/field/SporeFieldServer}(場から抜く)・{@code spore/SporeConcentration}(下支えを外す)。
 *
 * <h2>ユーザー指定(11-13)</h2>
 * <ul>
 *     <li>空気をきれいにする設備は、屋外の胞子の場から吸う掃除機だけ。場そのものから抜く・1 回に抜ける量に上限がある・範囲を 0 にはしない(決定 11)。</li>
 *     <li>地脈の力(FE)+ フィルターで動き、吸った胞子の分だけフィルターが詰まる。マスクと同じフィルター(決定 15)。</li>
 *     <li>フィルターは洗って何度か使える(洗うたびに少し傷む)。洗う手段は段 3(決定 18)。材料はバニラだけ(決定 27)。</li>
 * </ul>
 *
 * <h2>仮決め(数はすべて【仮】)</h2>
 * 30 FE/t(線の真上の汲み上げ器 40 FE/t で 1 台、電池 1 本で約 5.6 分)。範囲はチャンクの中心どうしで 2 チャンク(13 チャンク)。
 * 1 回の更新(5 秒)で場から抜ける上限 {@code capPerUpdate} 2.5(場の単位。過繁茂のチャンクの晴れた午後 = 0.55)。31×31 の過繁茂の森の真ん中で
 * 晴れの日は 1 日じゅうまわりがほぼ 0%、嵐では上限まで吸っても約 260%(抜き切れない)を場の式で測って決めた。
 * 抜いた量のフィルターへの詰まりは「抜いた量をチャンクの濃度(%)に直した値 × 更新の秒数 × {@code filterLoadFactor} 0.07」(%秒。マスクと同じ単位。深い森の晴れの日で 1 枚が約 1 日、嵐の中は約半日)。
 * 稼働中の機械の範囲では、汚染されたチャンクの下支え(繁茂 10%・過繁茂 25%)を外し、場の値だけで読む(決定 22 のため。10-22 の「下支えまでしか下げられない」から変わった)。
 */
public record VentSpec(
        int fePerTick,
        int energyBuffer,
        double radiusChunks,
        double capPerUpdate,
        double filterLoadFactor,
        int filterWashesMax,
        double filterWashWear,
        boolean lowerFloor) {

    /** Gson の受け皿(null は既定値)。 */
    public static final class Json {
        public String _comment;
        /** 動いている間に使う地脈の力(FE / tick)。 */
        public Integer fePerTick;
        /** 機械の中にためておける FE(線・汲み上げ器から受け取る器)。 */
        public Integer energyBuffer;
        /** 吸う範囲(チャンク。機械のいるチャンクの中心から、チャンクの中心どうしの距離)。 */
        public Double radiusChunks;
        /** 1 回の場の更新(updateTicks)で範囲から抜ける量の上限(場の単位。0.55 = 過繁茂のチャンク 1 つ分の濃さ 100%)。 */
        public Double capPerUpdate;
        /** 抜いた量をフィルターの詰まり(%秒)に直す倍率。詰まり = 抜いた量 ÷ fullAt × 100 × 更新の秒数 × この倍率。 */
        public Double filterLoadFactor;
        /** フィルターを洗える回数(段 3 の洗い場で使う。それまでは数を持つだけ)。 */
        public Integer filterWashesMax;
        /** 1 回洗うごとに減る容量の割合(新品の容量に対して)。 */
        public Double filterWashWear;
        /** 稼働中の機械の範囲で、汚染されたチャンクの下支えを外して場の値だけで読むか(false にすると場から抜くだけ。過繁茂の中では 25% より下がらない)。 */
        public Boolean lowerFloor;
    }

    public static final VentSpec DEFAULT = from(null);

    public static VentSpec from(Json j) {
        Json s = j != null ? j : new Json();
        return new VentSpec(
                i(s.fePerTick, 30, 0, 1_000_000),
                i(s.energyBuffer, 3_000, 1, 100_000_000),
                d(s.radiusChunks, 2.0D, 0.0D, 16.0D),
                d(s.capPerUpdate, 2.5D, 0.0D, 100.0D),
                d(s.filterLoadFactor, 0.07D, 0.0D, 1000.0D),
                i(s.filterWashesMax, 3, 0, 100),
                d(s.filterWashWear, 0.2D, 0.0D, 1.0D),
                s.lowerFloor == null || s.lowerFloor);
    }

    private static int i(Integer v, int def, int lo, int hi) {
        return v == null ? def : Math.max(lo, Math.min(hi, v));
    }

    private static double d(Double v, double def, double lo, double hi) {
        return v == null || !Double.isFinite(v) ? def : Math.max(lo, Math.min(hi, v));
    }
}
