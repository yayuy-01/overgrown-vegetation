package com.yayuy.overgrownvegetation.data;

/**
 * 疲労の設計値({@code spore_field.json} の {@code fatigue}、2026-11-13 サバイバルの背骨 段 1)。仕様の正本は Vault「サバイバルの背骨(籠城と遠征)」。
 * 読むのは {@link com.yayuy.overgrownvegetation.survival.FatigueTracker}。疲労は 0〜100(%)。
 *
 * <h2>ユーザー指定(11-13)</h2>
 * <ul>
 *     <li>たまる要因は 4 つ: 起きている時間 / 濃い胞子の中にいる / 走る・戦う・掘る / 感染の段階が高い。</li>
 *     <li>ふつうに過ごして寝なければ約 2 日(ゲーム内)で限界。</li>
 *     <li>効き目は 2 つ: 胞子に弱くなる(暴露量が抜けにくい・感染が進みやすい)/ 動きが鈍る。感染との悪循環は弱めにして上限を付ける。</li>
 *     <li>夜に寝ると全部戻る。昼でもベッドに横になれば、時間は飛ばさずに少しずつ戻る。死んで復活したら 0。</li>
 * </ul>
 *
 * <h2>仮決め(数はすべて【仮】)</h2>
 * 「ふつう」= 起きている + 2 割の時間を走る + 20 秒に 1 回殴る + 5 秒に 1 ブロック掘る・胞子なし・感染なし。
 * 0.025 + 0.05 × 0.2 + 0.05 / 20 + 0.02 / 5 = 0.0415 %/秒 → 100% まで約 2410 秒 ≈ 2.0 日(1 日 = 1200 秒)。
 */
public record FatigueSpec(
        boolean enabled,
        double awakePerSecond,
        double sprintPerSecond,
        double attackEach,
        double breakEach,
        double denseFromPercent,
        double densePerSecondAt100,
        double stagePerSecond,
        double restPerSecond,
        double effectsFrom,
        double moveSlow,
        double mineSlow,
        double attackSpeedSlow,
        double exposureHalfLifeMax,
        double infectionMultiplierMax,
        boolean dayRestNeedsSafety) {

    /** Gson の受け皿(null は既定値)。 */
    public static final class Json {
        public Boolean enabled;
        /** 起きているだけで 1 秒にたまる量(%)。 */
        public Double awakePerSecond;
        /** 走って(泳いで)いる 1 秒ごとに上乗せ(%)。 */
        public Double sprintPerSecond;
        /** 生き物を 1 回殴るごと(%)。 */
        public Double attackEach;
        /** ブロックを 1 つ壊すごと(%)。 */
        public Double breakEach;
        /** 濃い胞子: この濃度(%)から上乗せが始まる。 */
        public Double denseFromPercent;
        /** 濃い胞子: 濃度 100% のときの 1 秒の上乗せ(%。denseFromPercent から 100 まで直線)。防護マスクが効いていれば 0。 */
        public Double densePerSecondAt100;
        /** 感染の段階 1 つにつき 1 秒の上乗せ(%)。 */
        public Double stagePerSecond;
        /** ベッドに横になっている 1 秒ごとに戻る量(%。昼の休み・夜に寝て朝を待つ間)。夜を飛ばしたら全部戻る。 */
        public Double restPerSecond;
        /** 効き目が出始める疲労(%)。ここから 100 まで直線で強くなる。 */
        public Double effectsFrom;
        /** 100% のときの移動速度の低下(割合)。 */
        public Double moveSlow;
        /** 100% のときの採掘速度の低下(割合)。 */
        public Double mineSlow;
        /** 100% のときの攻撃速度の低下(割合)。 */
        public Double attackSpeedSlow;
        /** 100% のときの、暴露量の半減期の倍率(2 = 抜けるのに 2 倍かかる)。 */
        public Double exposureHalfLifeMax;
        /** 100% のときの、感染の進みの倍率(ユーザー指定「上限を付ける」。上限の数は仮決め)。 */
        public Double infectionMultiplierMax;
        /** 昼の休みでも、まわりに敵がいれば横になれない(夜と同じ判定)。 */
        public Boolean dayRestNeedsSafety;
    }

    public static final FatigueSpec DEFAULT = from(null);

    public static FatigueSpec from(Json j) {
        Json s = j != null ? j : new Json();
        double from = Math.min(99.0D, pos(s.effectsFrom, 50.0D));
        return new FatigueSpec(
                s.enabled == null || s.enabled,
                pos(s.awakePerSecond, 0.025D),
                pos(s.sprintPerSecond, 0.05D),
                pos(s.attackEach, 0.05D),
                pos(s.breakEach, 0.02D),
                Math.min(100.0D, pos(s.denseFromPercent, 20.0D)),
                pos(s.densePerSecondAt100, 0.05D),
                pos(s.stagePerSecond, 0.004D),
                pos(s.restPerSecond, 0.25D),
                from,
                Math.min(0.9D, pos(s.moveSlow, 0.15D)),
                Math.min(0.9D, pos(s.mineSlow, 0.2D)),
                Math.min(0.9D, pos(s.attackSpeedSlow, 0.1D)),
                Math.max(1.0D, pos(s.exposureHalfLifeMax, 2.0D)),
                Math.max(1.0D, pos(s.infectionMultiplierMax, 1.5D)),
                s.dayRestNeedsSafety == null || s.dayRestNeedsSafety);
    }

    private static double pos(Double v, double def) {
        return v != null && Double.isFinite(v) ? Math.max(0.0D, v) : def;
    }

    /** 効き目の強さ(0〜1): effectsFrom 以下は 0、100% で 1。 */
    public double effectStrength(double fatigue) {
        if (fatigue <= effectsFrom) return 0.0D;
        return Math.min(1.0D, (fatigue - effectsFrom) / (100.0D - effectsFrom));
    }

    /** 濃い胞子の 1 秒の上乗せ(%)。 */
    public double densePerSecond(double percent) {
        if (percent <= denseFromPercent || densePerSecondAt100 <= 0.0D) return 0.0D;
        double t = Math.min(1.0D, (percent - denseFromPercent) / Math.max(1.0E-6D, 100.0D - denseFromPercent));
        return densePerSecondAt100 * t;
    }
}
