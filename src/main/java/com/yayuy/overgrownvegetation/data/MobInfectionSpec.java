package com.yayuy.overgrownvegetation.data;

/**
 * Mob の胞子感染と飛び火・動物が汚染から逃げる(2026-11-14、{@code spore_field.json} の {@code mobInfection})。
 * 仕様の正本は Vault「フェーズの仕組み(ポイント・日数)」の F1。読むのは {@code spore/MobInfection}・{@code spore/SporeFleeGoal}。
 *
 * <h2>ユーザー指定(11-14)</h2>
 * <ul>
 *     <li>植物側以外の全部の Mob は、暴露量が上がる所(Mob の濃度が exposure.minPercent 以上)に {@code startSeconds} 5 秒いると胞子感染になる。</li>
 *     <li>感染した後は、その場を離れても毎秒暴露量が上がっていく。逃げた先で死に、そこからまた汚染が広がる(飛び火)。</li>
 *     <li>動物は汚染から逃げる。</li>
 * </ul>
 *
 * <h2>仮決め(数はすべて【仮】)</h2>
 * 感染中は暴露量が 1 秒に {@code growPerSecond} 12.5 %秒ずつ上がる(1500 で 20 ダメージなので、体力 20 以下の Mob はおよそ 2 分で死ぬ)。
 * 数えている間(感染前に浴びている間)は判定を {@code countStepTicks} 20 tick ごとにする(ふだんの Mob は 40 tick)。
 * 飛び火 = 感染した Mob が死んだチャンクへ、場の降り積もり {@code jumpDeposit}(発芽のしきい値 0.05 の 3 倍)と浮遊 {@code jumpEmit} を足す。
 * 同じチャンクへの飛び火は {@code jumpCooldownTicks} に 1 回。
 * 逃げる: 濃度が {@code fleeStartPercent}(0 = exposure.minPercent と同じ)以上の所で逃げ始め、出てから {@code fleeLingerTicks} 逃げ続ける。
 * 感染した後はずっと逃げる(汚染の無い所では最後の向きのまま)。1 回に {@code fleeDistance} ブロック先へ、速さの倍率は感染前 {@code fleeSpeed}・
 * 感染後 {@code infectedFleeSpeed}。粒は判定ごとに {@code particleCount} 個。
 * 感染後は弱って遅くなる(11-14 ユーザー): 歩く速さを {@code infectedSlowdown} 0.4 だけ下げ(属性の修飾・保存される)、逃げる向きから左右に
 * {@code wobbleDegrees} 50 度までふらつき、1 回に {@code infectedStepDistance} 8 ブロックずつ歩く(歩き直すたびに少し立ち止まる)。
 * 死ぬまでの約 2 分は変えず、走る距離を 60〜100 ブロックに縮めて Mob の動く範囲の中で死ぬようにするため。
 */
public record MobInfectionSpec(
        boolean enabled,
        double startSeconds,
        int countStepTicks,
        double growPerSecond,
        double jumpDeposit,
        double jumpEmit,
        int jumpCooldownTicks,
        boolean fleeEnabled,
        double fleeStartPercent,
        int fleeLingerTicks,
        int fleeDistance,
        double fleeSpeed,
        double infectedFleeSpeed,
        int particleCount,
        double infectedSlowdown,
        double wobbleDegrees,
        int infectedStepDistance) {

    /** Gson の受け皿(null は既定値)。 */
    public static final class Json {
        public String _comment;
        /** false で感染も飛び火も逃げるのも止める(今までの「1500 で 20 ダメージ」だけになる)。 */
        public Boolean enabled;
        /** 暴露量が上がる所にこの秒数いると感染する(ユーザー指定 5)。 */
        public Double startSeconds;
        /** 数えている間の判定の間隔(tick)。 */
        public Integer countStepTicks;
        /** 感染中に暴露量が 1 秒に上がる量(%秒)。濃い所ではその分も足す。 */
        public Double growPerSecond;
        /** 飛び火: 死んだチャンクへ足す降り積もり(場の単位。発芽のしきい値 field.germThreshold と比べる)。 */
        public Double jumpDeposit;
        /** 飛び火: 死んだチャンクへ足す浮遊(過繁茂 1 チャンクの 1 日の放出 = 1)。 */
        public Double jumpEmit;
        /** 同じチャンクへの飛び火の間隔(tick)。 */
        public Integer jumpCooldownTicks;
        /** 動物が汚染から逃げるか。 */
        public Boolean fleeEnabled;
        /** この濃度(%)以上で逃げ始める。0 = exposure.minPercent と同じ。 */
        public Double fleeStartPercent;
        /** 濃い所を出てから逃げ続ける tick。 */
        public Integer fleeLingerTicks;
        /** 1 回に逃げる先の距離(ブロック)。 */
        public Integer fleeDistance;
        /** 逃げる速さの倍率(感染前・感染後)。 */
        public Double fleeSpeed, infectedFleeSpeed;
        /** 感染した Mob から判定ごとに出る胞子の粒の数。 */
        public Integer particleCount;
        /** 感染後の歩く速さの減り(0.4 = 40% 遅く。体の中の胞子が養分を奪う、11-14 ユーザー「感染後は弱って遅くなる」)。 */
        public Double infectedSlowdown;
        /** 感染後にふらつく角度(逃げる向きから左右にこの度数まで乱れる)。 */
        public Double wobbleDegrees;
        /** 感染後に 1 回に歩く先の距離(ブロック。短いほど立ち止まりが増える)。 */
        public Integer infectedStepDistance;
    }

    public static final MobInfectionSpec DEFAULT = from(null);

    public static MobInfectionSpec from(Json j) {
        Json s = j != null ? j : new Json();
        return new MobInfectionSpec(
                s.enabled == null || s.enabled,
                d(s.startSeconds, 5.0D, 0.0D, 3600.0D),
                i(s.countStepTicks, 20, 1, 200),
                d(s.growPerSecond, 12.5D, 0.0D, 100_000.0D),
                d(s.jumpDeposit, 0.15D, 0.0D, 100.0D),
                d(s.jumpEmit, 0.1D, 0.0D, 100.0D),
                i(s.jumpCooldownTicks, 1200, 0, 10_000_000),
                s.fleeEnabled == null || s.fleeEnabled,
                d(s.fleeStartPercent, 0.0D, 0.0D, 10_000.0D),
                i(s.fleeLingerTicks, 60, 0, 100_000),
                i(s.fleeDistance, 16, 4, 64),
                d(s.fleeSpeed, 1.25D, 0.1D, 5.0D),
                d(s.infectedFleeSpeed, 1.0D, 0.1D, 5.0D),
                i(s.particleCount, 2, 0, 64),
                d(s.infectedSlowdown, 0.4D, 0.0D, 0.95D),
                d(s.wobbleDegrees, 50.0D, 0.0D, 180.0D),
                i(s.infectedStepDistance, 8, 2, 64));
    }

    private static int i(Integer v, int def, int lo, int hi) {
        return v == null ? def : Math.max(lo, Math.min(hi, v));
    }

    private static double d(Double v, double def, double lo, double hi) {
        return v == null || !Double.isFinite(v) ? def : Math.max(lo, Math.min(hi, v));
    }
}
