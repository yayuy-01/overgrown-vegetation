package com.yayuy.overgrownvegetation.data;

/**
 * 地脈の最小限の設計値({@code spore_field.json} の {@code ley}、2026-11-13 サバイバルの背骨 段 2b)。仕様の正本は Vault「サバイバルの背骨(籠城と遠征)」の決定 8・13・14・19・20・23〜25。
 * 読むのは {@link com.yayuy.overgrownvegetation.corruption.LeyLines}(線の場)と {@code ley/} の部品(汲み上げ器・電池・柱と線・地脈の花)。
 *
 * <h2>ユーザー指定(11-13)</h2>
 * <ul>
 *     <li>地脈は大地に流れるエネルギー。吸い上げ、配線して装置へ送るか、電池にためて電池で動かす(決定 8)。</li>
 *     <li>配線は柱から柱へ線を張る形(2 か所をクリック)(決定 13)。汲み上げ器はどこでも置け、線に近いほど多く汲める(線の真上で 100%)(決定 20)。</li>
 *     <li>エネルギーは FE(NeoForge の {@code IEnergyStorage})で数え、受け渡しも FE の口(決定 23)。線の正本は {@code LeyLines}(決定 24)。</li>
 *     <li>地脈の花は世界を作るときに線の上に生え、遊んでいる間にも線の上へ少しずつ増える(決定 25)。</li>
 * </ul>
 *
 * <h2>仮決め(数はすべて【仮】)</h2>
 * 線の場の形(セル 192・歪み 0.35)は前の地脈と同じ(変えると洞窟の地脈の根・巨大樹の光も一緒に動く)。
 * 汲み上げ器は線の真上で 40 FE/t(FE の相場: 序盤の発電機が 20〜80 FE/t)、線から {@code pumpReach} 離れると {@code pumpMinEfficiency} まで直線に下がる。
 * 換気の機械(2c)を 20〜40 FE/t と見て「線の真上の汲み上げ器 1 台で機械 1〜2 台」。電池 200,000 FE(20 FE/t の機械を 10,000 tick ≒ 8.3 分 = ゲーム内の半日弱動かす。
 * 線の真上の汲み上げ器 1 台で空から満タンまで 5,000 tick ≒ 4 分)。線は 24 ブロックまで・1 本の柱に 4 本まで・1 つの網で 1 tick 1,000 FE まで。
 */
public record LeySpec(
        double cellSize,
        double warp,
        double flowerBand,
        int flowerWorldgenTries,
        int flowerSpreadIntervalTicks,
        int flowerSpreadTries,
        int flowerSpreadRadius,
        int flowerCrowdRadius,
        int flowerCrowdMax,
        int pumpFePerTick,
        double pumpReach,
        double pumpMinEfficiency,
        int pumpBuffer,
        int pumpMaxOutput,
        int batteryCapacity,
        int batteryMaxTransfer,
        int wireMaxLength,
        int wireMaxPerPylon,
        int wireNetworkPerTick,
        boolean wireNeedsClearPath) {

    /** Gson の受け皿(null は既定値)。 */
    public static final class Json {
        public String _comment;
        /** 線の場の網目の粗さ(セル 1 つの 1 辺、ブロック)。線どうしの間はおよそこの長さ。洞窟の地脈の根・巨大樹の光も同じ線を使う。 */
        public Double cellSize;
        /** 線をうねらせる強さ(セルの大きさに対する割合。0 = まっすぐな多角形)。 */
        public Double warp;
        /** 地脈の花が生える帯の半分の幅(線からこの距離以内、ブロック)。 */
        public Double flowerBand;
        /** 世界を作るとき、1 チャンクで花を置く場所を試す回数。 */
        public Integer flowerWorldgenTries;
        /** 遊んでいる間に花が増える間隔(tick)。 */
        public Integer flowerSpreadIntervalTicks;
        /** 1 回に、プレイヤー 1 人のまわりで試す回数。 */
        public Integer flowerSpreadTries;
        /** 増える場所を探す半径(ブロック、プレイヤーから)。 */
        public Integer flowerSpreadRadius;
        /** 混み具合を数える半径(ブロック)。 */
        public Integer flowerCrowdRadius;
        /** その半径の中にこれだけ花があれば増やさない。 */
        public Integer flowerCrowdMax;
        /** 汲み上げ器が線の真上で汲む量(FE / tick)。 */
        public Integer pumpFePerTick;
        /** 効率がいちばん低くなる、線からの距離(ブロック)。 */
        public Double pumpReach;
        /** 線から遠い所の効率(0〜1)。 */
        public Double pumpMinEfficiency;
        /** 汲み上げ器の中にためられる量(FE)。 */
        public Integer pumpBuffer;
        /** 汲み上げ器が 1 tick に出せる量(FE、隣の機械・線・電池の合計)。 */
        public Integer pumpMaxOutput;
        /** 電池の容量(FE)。 */
        public Integer batteryCapacity;
        /** 電池が 1 tick に出し入れできる量(FE)。 */
        public Integer batteryMaxTransfer;
        /** 線を張れる長さ(ブロック、端から端のまっすぐな距離)。 */
        public Integer wireMaxLength;
        /** 1 本の柱につなげる線の数。 */
        public Integer wireMaxPerPylon;
        /** 1 つの網(線でつながった柱のまとまり)が 1 tick に運べる量(FE)。 */
        public Integer wireNetworkPerTick;
        /** 線を張るとき、間に当たり判定のあるブロックがあれば張らない。 */
        public Boolean wireNeedsClearPath;
    }

    public static final LeySpec DEFAULT = from(null);

    public static LeySpec from(Json j) {
        Json s = j != null ? j : new Json();
        return new LeySpec(
                d(s.cellSize, 192.0D, 16.0D, 4096.0D),
                d(s.warp, 0.35D, 0.0D, 2.0D),
                d(s.flowerBand, 2.5D, 0.5D, 16.0D),
                i(s.flowerWorldgenTries, 12, 0, 256),
                i(s.flowerSpreadIntervalTicks, 200, 1, 72000),
                i(s.flowerSpreadTries, 4, 0, 256),
                i(s.flowerSpreadRadius, 48, 4, 256),
                i(s.flowerCrowdRadius, 3, 0, 16),
                i(s.flowerCrowdMax, 2, 0, 64),
                i(s.pumpFePerTick, 40, 0, 1_000_000),
                d(s.pumpReach, 96.0D, 1.0D, 4096.0D),
                d(s.pumpMinEfficiency, 0.05D, 0.0D, 1.0D),
                i(s.pumpBuffer, 20_000, 1, 100_000_000),
                i(s.pumpMaxOutput, 200, 1, 1_000_000),
                i(s.batteryCapacity, 200_000, 1, 1_000_000_000),
                i(s.batteryMaxTransfer, 400, 1, 1_000_000),
                i(s.wireMaxLength, 24, 2, 128),
                i(s.wireMaxPerPylon, 4, 1, 32),
                i(s.wireNetworkPerTick, 1_000, 1, 100_000_000),
                s.wireNeedsClearPath == null || s.wireNeedsClearPath);
    }

    private static int i(Integer v, int def, int lo, int hi) {
        return v == null ? def : Math.max(lo, Math.min(hi, v));
    }

    private static double d(Double v, double def, double lo, double hi) {
        return v == null || !Double.isFinite(v) ? def : Math.max(lo, Math.min(hi, v));
    }
}
