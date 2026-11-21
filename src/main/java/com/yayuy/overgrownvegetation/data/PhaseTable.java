package com.yayuy.overgrownvegetation.data;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code phases.json} — フェーズ 1〜10 の昇格条件と内容。
 *
 * <h2>昇格条件</h2>
 * <p>昇格は<b>ポイントだけ</b>で決まる({@code pointsToAdvance})。ポイントは<b>直前のフェーズが
 * 始まった時点からの増分</b>({@link com.yayuy.overgrownvegetation.phase.PhaseData} がフェーズ昇格の
 * たびに 0 へ戻す)。{@code pointsToAdvance} が無い(null / 0)フェーズへは<b>自動昇格しない</b>
 * (2026-09-26 からは既定でフェーズ10 まで全部に条件があり、F7 以降も自動で上がる)。
 *
 * <p>ポイント(2026-11-14 夕 ユーザー決定): 時間 12 秒で 1 点(1 日 100)+ 面積 毎秒 今の過繁茂のチャンク数 × 0.01(上限なし。押し返すと減る)
 * + 撃破 1 体で今のフェーズの閾値の 0.1〜1%(上限なし)。閾値は計測から決める(F7 まで約 75 日)。今の値は 11-14 夜の計測の案【仮】。
 * 台帳の広がり({@code corruption/LedgerSpread}、5 分おきに縁を面の数 × 25% の確率で塗る)の平均の増え方で、
 * F1→F2〜F4→F5 が各 5 日、F5→F6・F6→F7 が各 20 日、F7→F8〜F9→F10 が各 10 日(F1→F10 で 90 日)。
 * フェーズ0→1 だけは別枠で、{@code global.startWorldAgeTicks}(既定 3 日)と
 * {@code global.startAdvancements}(既定 6 個の実績)の<b>両方</b>が条件。
 * 判定そのものは {@link #canAdvanceTo(int, long)} に集約してある。
 *
 * <p>2026-09-21 に、「どの種がどのフェーズのどこに湧くか」({@code spawnGroups} /
 * {@code outsideSpawns.types} / {@code carrierSpawns.types})は <b>{@code mobs.json} の
 * {@code spawns}</b> へ移した。2026-11-15 に、読む所の無くなった湧きの欄({@code outsideSpawns}・{@code carrierSpawns}・{@code mixedPacks}・
 * {@code raidWaveGroups}・{@code raidEnabled}・{@code tdEnabled})を消した。敵の湧き方は {@code spawns.json}({@code spawn/EnemySpawns})。
 *
 * <p>フェーズの内容の既定値は、現行の散在ロジック
 * ({@code OvergrownBiomeSpawner.spawnPoolFor} / {@code GroupPhaseBehavior} /
 * {@code Group1FleeCorruptionGoal} / {@code RaidData} / {@code RaidWaveSpawner.poolFor})を
 * 写経したもの({@link #DEFAULT})。
 */
public final class PhaseTable {

    public static final int MIN_PHASE = 1;
    public static final int MAX_PHASE = 10;

    // --- Gson DTO ---

    public static final class Json {
        public Global global;
        /** キー "1".."10" → その フェーズの定義。 */
        public java.util.Map<String, Phase> phases;
    }

    public static final class Global {
        /** フェーズ0→1 の時刻ゲート(ワールド生成からの経過 tick)。 */
        public Integer startWorldAgeTicks;
        /**
         * フェーズ0→1 の実績ゲート。ワールド全体で解除された(表示される)実績の種類数が
         * これに達するまでフェーズ1は始まらない。{@code startWorldAgeTicks} との<b>両方</b>が条件
         * (2026-09-15)。0 で実績ゲートを無効化。
         */
        public Integer startAdvancements;
        public Points points;
        /** フェーズ1の前の「前兆」(2026-10-24 地脈技術の 4 段目。数値はどれも仮)。 */
        public OmenJson omen;
        /** 小さな段(導入 → 定着 → 最盛)の既定の倍率と区切り(2026-11-14)。フェーズごとの {@code stages} が無い欄はここを使う。 */
        public Stages stages;
    }

    /**
     * フェーズの中の小さな段(2026-11-14 ユーザー決定: どのフェーズも 導入 → 定着 → 最盛 の 3 つ)。
     * そのフェーズの新しい物は導入でまれに、定着で普通に、最盛でいちばん多く強くなる。段ごとに新しい物は作らない。
     *
     * <ul>
     *   <li>{@code intro} / {@code established} / {@code peak}: その段での「そのフェーズの物の強さ」の倍率
     *   (【仮】0.3 / 0.7 / 1.0)。読むのは {@link com.yayuy.overgrownvegetation.phase.PhaseStages#strength}。</li>
     *   <li>{@code establishedFrom} / {@code peakFrom}: 次のフェーズの {@code pointsToAdvance} に対する進みが
     *   これを越えたら定着・最盛(【仮】1/3・2/3)。</li>
     * </ul>
     * {@code global.stages} が既定、各フェーズの {@code stages} は書いた欄だけ上書き。
     */
    public static final class Stages {
        public Double intro;
        public Double established;
        public Double peak;
        public Double establishedFrom;
        public Double peakFrom;
    }

    /**
     * 前兆(F1 の前に「何かおかしい」と気付ける出来事)。どれも仮決め 2026-10-24。
     * 始まりと未知の試料の解禁は「地割れの発生日時」からの引き算(既定 3 日 − 0.5 日 = 2.5 日、3 日 − 1 日 = 2 日)。
     */
    public static final class OmenJson {
        /** F1 の時刻の条件のこれだけ前から前兆(既定 12000 = 0.5 日 → 2.5 日目)。 */
        public Integer leadTicks;
        /** F1 の時刻の条件のこれだけ前から、汲み上げ器の雫に未知の試料が混じる(既定 24000 = 1 日 → 2 日目)。 */
        public Integer sampleLeadTicks;
        /** 雫 1 つが試料になる確率(既定 0.05)。 */
        public Double sampleChance;
        /** 汲み上げ器の効率の揺れ幅(既定 0.4 = ±40%)。 */
        public Double pumpJitter;
        /** 揺れを引き直す間隔(既定 100 tick = 5 秒)。 */
        public Integer pumpJitterTicks;
        /** 汲み上げ器の近くのプレイヤーへの知らせの間隔(既定 12000 = 10 分)と範囲(既定 32)。 */
        public Integer noticeTicks;
        public Integer noticeRadius;
        /** 軽い地鳴りの間隔(既定 12000〜24000 = 10〜20 分)と、プレイヤーからの距離(既定 16〜48)。 */
        public Integer quakeMinTicks;
        public Integer quakeMaxTicks;
        public Integer quakeMinDistance;
        public Integer quakeMaxDistance;
        /** 揺れの強さの最大(地割れの既定 1.0 に対して。既定 0.4)と長さ(既定 100 tick)。 */
        public Double quakeIntensity;
        public Integer quakeTicks;
        /** 地脈の花がランダム tick 1 回でしおれる確率(既定 0.1)・F1 の後に元に戻る確率(既定 0.15 ≒ 1 日で 9 割)。 */
        public Double flowerWiltChance;
        public Double flowerRecoverChance;
        /** 家畜が線から離れる範囲 = 線の幅 + これ(既定 6)。 */
        public Integer animalAvoidExtra;
    }

    /** 解決済みの前兆の値。 */
    public record Omen(int leadTicks, int sampleLeadTicks, double sampleChance, double pumpJitter, int pumpJitterTicks,
                       int noticeTicks, int noticeRadius, int quakeMinTicks, int quakeMaxTicks,
                       int quakeMinDistance, int quakeMaxDistance, double quakeIntensity, int quakeTicks,
                       double flowerWiltChance, double flowerRecoverChance, int animalAvoidExtra) {
    }

    /**
     * ポイントの獲得条件(2026-11-14 夕 ユーザー決定。正本は Vault「フェーズの仕組み」の「ポイントの配分とバイオマスの収支」)。
     * 方針: 進み方は大きく変わってよい(植物側の Mob を倒しまくれば無茶苦茶速く進む)。古い鍵(perTick・perTickPerOvergrownChunk・
     * perGroupMobDeath・perGroupMobKill・perOvergrownChunk・areaPerDay・killsPerDayCap)は読み飛ばす。
     */
    public static final class Points {
        /** 実績を 1 つ解除するたび。【仮】0(F1 より後は付けない。F1 の始まりの「実績 6 つ」とは別)。 */
        public Integer perAdvancement;
        /** 時間: 1 日(24000 tick)でこれだけ。ユーザー指定「12 秒で 1 点」= 100。 */
        public Double timePerDay;
        /**
         * 面積: 毎秒「今の過繁茂のチャンク数 × これ」点。ユーザー指定 0.01(1 日でチャンク数 × 12 点)。累計ではなく今の数、上限なし。
         * 今の数 = 台帳({@code PhaseData#overgrownLedgerSize})= 胞子の場で発芽したチャンク(繁茂の札の 2 日 + 過繁茂)。
         */
        public Double areaPerChunkPerSecond;
        /** 撃破: プレイヤーが倒した植物側の Mob 1 体で、今のフェーズの閾値(次のフェーズの pointsToAdvance)の何 %。上限なし。 */
        public KillPoints kills;
    }

    /** 撃破の点(閾値に対する %。ユーザー 11-14)。G1 の 1 段目(発達した寄生体)= evolved、それ以外の G1(段階 0・根付き・苗床)= stage0。 */
    public static final class KillPoints {
        public Double stage0;
        public Double evolved;
        public Double g2;
        public Double g3;
        public Double g4;
    }

    public static final class Phase {
        /**
         * 直前のフェーズ開始時点から、このフェーズへ進むのに必要な累計ポイント(null / 0 で条件なし
         * = そのフェーズへは自動昇格しない)。昇格条件はこれだけ
         * (チャンク数による昇格は 2026-09-15 に廃止)。
         */
        public Long pointsToAdvance;
        /**
         * このフェーズの「ふつうの広さ」(今の過繁茂のチャンク数、2026-11-14)。司令塔のバイオマスの物差し(収入 × 今の数 ÷ これ)。点の式には使わない。
         * 【仮】計測で決める(動物の多い普通の世界で、プレイヤーのそばで、そのフェーズの日の真ん中あたりの面積)。
         */
        public Integer normalArea;
        /**
         * このフェーズへ<b>自然に昇格するか</b>(2026-09-29 ユーザー指定、既定 true)。
         * false にするとポイントが足りていても上がらない —— {@code /ov phase set} だけで入れる。
         * {@code pointsToAdvance} を 0 にしても同じことはできるが、それだと<b>数値を捨てる</b>ことになるので、
         * 止めたいだけのときはこちらを使う。
         */
        public Boolean autoAdvance;
        /** 自由記述の内容メモ(挙動には影響しない — マネージャー表示用)。 */
        public String notes;
        /** このフェーズの小さな段の倍率と区切り(2026-11-14。書いた欄だけ {@code global.stages} を上書き)。{@link Stages}。 */
        public Stages stages;
    }

    // --- 解決済みの値 ---

    /** フェーズごとの段の倍率 [導入, 定着, 最盛](index 1..10)。 */
    private final double[][] stageMultipliers = new double[MAX_PHASE + 1][3];
    /** フェーズごとの段の区切り [定着から, 最盛から](次のフェーズの閾値に対する割合、index 1..10)。 */
    private final double[][] stageBounds = new double[MAX_PHASE + 1][2];

    private final int startWorldAgeTicks;
    private final int startAdvancements;
    private final int pointsPerAdvancement;
    private final double timePointsPerDay;
    private final double areaPointsPerChunkPerSecond;
    private final double[] killPercent = new double[5]; // stage0・evolved・g2・g3・g4(閾値の %)
    private final Phase[] phases; // index 1..10
    private final Omen omen;

    private PhaseTable(Json j) {
        Json d = DEFAULT_JSON;
        Global g = j != null && j.global != null ? j.global : new Global();
        Global dg = d.global;
        this.startWorldAgeTicks = orElse(g.startWorldAgeTicks, dg.startWorldAgeTicks);
        this.startAdvancements = orElse(g.startAdvancements, dg.startAdvancements);
        Points p = g.points != null ? g.points : new Points();
        Points dp = dg.points;
        this.pointsPerAdvancement = Math.max(0, orElse(p.perAdvancement, dp.perAdvancement));
        this.timePointsPerDay = Math.max(0.0D, orElse(p.timePerDay, dp.timePerDay));
        this.areaPointsPerChunkPerSecond = Math.max(0.0D, orElse(p.areaPerChunkPerSecond, dp.areaPerChunkPerSecond));
        KillPoints k = p.kills != null ? p.kills : new KillPoints();
        KillPoints dk = dp.kills;
        this.killPercent[0] = Math.max(0.0D, orElse(k.stage0, dk.stage0));
        this.killPercent[1] = Math.max(0.0D, orElse(k.evolved, dk.evolved));
        this.killPercent[2] = Math.max(0.0D, orElse(k.g2, dk.g2));
        this.killPercent[3] = Math.max(0.0D, orElse(k.g3, dk.g3));
        this.killPercent[4] = Math.max(0.0D, orElse(k.g4, dk.g4));
        OmenJson o = g.omen != null ? g.omen : new OmenJson();
        OmenJson od = dg.omen;
        this.omen = new Omen(orElse(o.leadTicks, od.leadTicks), orElse(o.sampleLeadTicks, od.sampleLeadTicks),
                orElse(o.sampleChance, od.sampleChance), orElse(o.pumpJitter, od.pumpJitter),
                Math.max(1, orElse(o.pumpJitterTicks, od.pumpJitterTicks)),
                Math.max(20, orElse(o.noticeTicks, od.noticeTicks)), orElse(o.noticeRadius, od.noticeRadius),
                Math.max(20, orElse(o.quakeMinTicks, od.quakeMinTicks)), Math.max(20, orElse(o.quakeMaxTicks, od.quakeMaxTicks)),
                orElse(o.quakeMinDistance, od.quakeMinDistance), orElse(o.quakeMaxDistance, od.quakeMaxDistance),
                orElse(o.quakeIntensity, od.quakeIntensity), Math.max(20, orElse(o.quakeTicks, od.quakeTicks)),
                orElse(o.flowerWiltChance, od.flowerWiltChance), orElse(o.flowerRecoverChance, od.flowerRecoverChance),
                orElse(o.animalAvoidExtra, od.animalAvoidExtra));

        this.phases = new Phase[MAX_PHASE + 1];
        for (int n = MIN_PHASE; n <= MAX_PHASE; n++) {
            Phase def = DEFAULT_JSON.phases.get(String.valueOf(n));
            Phase over = j != null && j.phases != null ? j.phases.get(String.valueOf(n)) : null;
            this.phases[n] = merge(def, over);
        }

        // 小さな段(2026-11-14): フェーズの欄 → global の欄 → 既定 の順に引く。
        Stages gs = g.stages != null ? g.stages : new Stages();
        Stages ds = dg.stages;
        for (int n = MIN_PHASE; n <= MAX_PHASE; n++) {
            Stages ps = this.phases[n].stages != null ? this.phases[n].stages : new Stages();
            stageMultipliers[n][0] = Math.max(0.0D, pick(ps.intro, gs.intro, ds.intro));
            stageMultipliers[n][1] = Math.max(0.0D, pick(ps.established, gs.established, ds.established));
            stageMultipliers[n][2] = Math.max(0.0D, pick(ps.peak, gs.peak, ds.peak));
            double from1 = clamp01(pick(ps.establishedFrom, gs.establishedFrom, ds.establishedFrom));
            double from2 = clamp01(pick(ps.peakFrom, gs.peakFrom, ds.peakFrom));
            stageBounds[n][0] = from1;
            stageBounds[n][1] = Math.max(from1, from2);
        }
    }

    private static double pick(Double first, Double second, Double fallback) {
        return first != null ? first : second != null ? second : fallback;
    }

    private static double clamp01(double v) {
        return Math.max(0.0D, Math.min(1.0D, v));
    }

    public static PhaseTable fromJson(Json j) {
        return new PhaseTable(j);
    }

    // --- 小さな段(導入 → 定着 → 最盛、2026-11-14) ---

    /**
     * フェーズ {@code phase} の中で段を決める物差しのポイント = <b>次のフェーズの {@code pointsToAdvance}</b>
     * (ポイントはフェーズが上がるたびに 0 へ戻るので、フェーズ p の進みは「p+1 へ上がる閾値」に対する割合)。
     * {@code autoAdvance: false}(F8 以降)でも閾値の数字が書いてあれば使う —— F7〜F9 もポイントは貯まり続けるので、
     * 段は最盛まで進んでそこで止まる。次のフェーズが無い(F10)・閾値が無いときは {@link Long#MAX_VALUE}
     * (= 段はポイントでは進まない。{@code PhaseData} が持っている段のまま)。
     */
    public long stageSpanPoints(int phase) {
        if (phase < MIN_PHASE || phase >= MAX_PHASE) {
            return Long.MAX_VALUE;
        }
        Long v = phases[phase + 1].pointsToAdvance;
        return v != null && v > 0 ? v : Long.MAX_VALUE;
    }

    /** その段の倍率(【仮】導入 0.3 / 定着 0.7 / 最盛 1.0)。 */
    public double stageMultiplier(int phase, com.yayuy.overgrownvegetation.phase.PhaseStage stage) {
        int n = Math.max(MIN_PHASE, Math.min(MAX_PHASE, phase));
        return stageMultipliers[n][stage.ordinal()];
    }

    /** その段が始まる進みの割合(導入 = 0、定着 = {@code establishedFrom}、最盛 = {@code peakFrom})。 */
    public double stageFrom(int phase, com.yayuy.overgrownvegetation.phase.PhaseStage stage) {
        int n = Math.max(MIN_PHASE, Math.min(MAX_PHASE, phase));
        return switch (stage) {
            case INTRO -> 0.0D;
            case ESTABLISHED -> stageBounds[n][0];
            case PEAK -> stageBounds[n][1];
        };
    }

    /**
     * その段が始まるポイント(切り上げ)。物差しが無いフェーズは -1。
     * {@code points >= stageStartPoints(p, s)} なら段 s 以上。
     */
    public long stageStartPoints(int phase, com.yayuy.overgrownvegetation.phase.PhaseStage stage) {
        long span = stageSpanPoints(phase);
        if (span == Long.MAX_VALUE) {
            return -1L;
        }
        return (long) Math.ceil(span * stageFrom(phase, stage));
    }

    /**
     * ポイントから段を求める(純粋な計算。テストもここを見る)。物差しが無いフェーズは null
     * (= ポイントでは決まらない)。閾値を越えていても最盛のまま(上がらないフェーズの F7〜F9)。
     */
    @javax.annotation.Nullable
    public com.yayuy.overgrownvegetation.phase.PhaseStage stageFor(int phase, long points) {
        if (stageSpanPoints(phase) == Long.MAX_VALUE) {
            return null;
        }
        if (points >= stageStartPoints(phase, com.yayuy.overgrownvegetation.phase.PhaseStage.PEAK)) {
            return com.yayuy.overgrownvegetation.phase.PhaseStage.PEAK;
        }
        if (points >= stageStartPoints(phase, com.yayuy.overgrownvegetation.phase.PhaseStage.ESTABLISHED)) {
            return com.yayuy.overgrownvegetation.phase.PhaseStage.ESTABLISHED;
        }
        return com.yayuy.overgrownvegetation.phase.PhaseStage.INTRO;
    }

    /** このフェーズの進み(0〜1、閾値を越えたら 1)。物差しが無いフェーズは -1。 */
    public double stageProgress(int phase, long points) {
        long span = stageSpanPoints(phase);
        if (span == Long.MAX_VALUE) {
            return -1.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, points / (double) span));
    }

    public int startWorldAgeTicks() {
        return startWorldAgeTicks;
    }

    /** F1 の前の前兆の値(2026-10-24)。 */
    public Omen omen() {
        return omen;
    }

    /** フェーズ1が始まるのに必要な「解除済み実績の種類数」(0 で実績ゲートなし)。 */
    public int startAdvancements() {
        return startAdvancements;
    }

    public int pointsPerAdvancement() {
        return pointsPerAdvancement;
    }

    /** 時間の点(1 日)。 */
    public double timePointsPerDay() {
        return timePointsPerDay;
    }

    /** 面積の点: 毎秒、今の過繁茂のチャンク 1 つあたり。 */
    public double areaPointsPerChunkPerSecond() {
        return areaPointsPerChunkPerSecond;
    }

    /** フェーズ {@code n} の「ふつうの広さ」(今の過繁茂のチャンク数)。点の式には使わない —— 司令塔のバイオマスの物差し(司令塔が読む)。無ければ 0。 */
    public int normalArea(int n) {
        if (n < MIN_PHASE || n > MAX_PHASE) {
            return 0;
        }
        Integer v = phases[n].normalArea;
        return v != null && v > 0 ? v : 0;
    }

    /**
     * 今の過繁茂のチャンク数が {@code area} のときの、1 tick あたりの時間 + 面積の点(2026-11-14 夕)。
     * = timePerDay ÷ 24000 + area × areaPerChunkPerSecond ÷ 20。上限は付けない(ユーザー)。
     */
    public double pointsPerTick(long area) {
        return timePointsPerDay / TICKS_PER_DAY + Math.max(0L, area) * areaPointsPerChunkPerSecond / 20.0D;
    }

    /** 撃破 1 体の、閾値に対する %。{@code kind}: 0 = 段階 0・1 = 進化体・2 = G2・3 = G3・4 = G4。 */
    public double killPercent(int kind) {
        return kind >= 0 && kind < killPercent.length ? killPercent[kind] : 0.0D;
    }

    /** フェーズ {@code phase} で撃破 1 体({@code kind})の点 = 次のフェーズの閾値 × %。閾値が無ければ 0。 */
    public double killPoints(int phase, int kind) {
        long span = stageSpanPoints(phase);
        return span == Long.MAX_VALUE ? 0.0D : span * killPercent(kind) / 100.0D;
    }

    /** 1 日の長さ(tick)。 */
    public static final long TICKS_PER_DAY = 24000L;

    /** {@code n} フェーズへ進むのに必要なポイント。条件が無ければ {@link Long#MAX_VALUE}。 */
    public long pointsToAdvance(int n) {
        if (!inAdvanceRange(n)) {
            return Long.MAX_VALUE;
        }
        Long v = phases[n].pointsToAdvance;
        return v != null && v > 0 ? v : Long.MAX_VALUE;
    }

    /**
     * フェーズ {@code n} へ昇格できるか。判定はポイントだけ
     * (過繫茂系チャンク数による昇格は 2026-09-15 に廃止。チャンクは
     * 面積は {@link #pointsPerTick} 経由でポイントの貯まる速さにだけ効く)。
     *
     * @param points 直前のフェーズ開始時点からの獲得ポイント
     * @return 条件を満たしていれば true。{@code pointsToAdvance} が設定されていないフェーズは常に false
     *         (= そこへは自動昇格しない)。
     */
    public boolean canAdvanceTo(int n, long points) {
        if (!autoAdvance(n)) {
            return false;
        }
        long need = pointsToAdvance(n);
        return need != Long.MAX_VALUE && points >= need;
    }

    /** このフェーズへ自然に昇格するか({@code autoAdvance}、未設定なら true)。 */
    public boolean autoAdvance(int n) {
        Boolean v = phase(n).autoAdvance;
        return v == null || v;
    }
    /** 自動昇格の対象になりうるフェーズ番号か(フェーズ1は開始フェーズなので対象外)。 */
    private static boolean inAdvanceRange(int n) {
        return n >= MIN_PHASE + 1 && n <= MAX_PHASE;
    }

    public Phase phase(int n) {
        int clamped = Math.max(MIN_PHASE, Math.min(MAX_PHASE, n));
        return phases[clamped];
    }

    // --- merge helpers ---

    private static Phase merge(Phase def, Phase over) {
        Phase r = new Phase();
        r.pointsToAdvance = over != null && over.pointsToAdvance != null ? over.pointsToAdvance : def.pointsToAdvance;
        r.normalArea = over != null && over.normalArea != null ? over.normalArea : def.normalArea;
        r.autoAdvance = over != null && over.autoAdvance != null ? over.autoAdvance : def.autoAdvance;
        r.notes = over != null && over.notes != null ? over.notes : def.notes;
        // 小さな段(2026-11-14)。フェーズの欄は書いた欄だけ。global との重ね合わせはコンストラクタで行う。
        Stages ds = def.stages;
        Stages os = over != null ? over.stages : null;
        if (ds != null || os != null) {
            Stages s = new Stages();
            s.intro = os != null && os.intro != null ? os.intro : (ds != null ? ds.intro : null);
            s.established = os != null && os.established != null ? os.established : (ds != null ? ds.established : null);
            s.peak = os != null && os.peak != null ? os.peak : (ds != null ? ds.peak : null);
            s.establishedFrom = os != null && os.establishedFrom != null ? os.establishedFrom : (ds != null ? ds.establishedFrom : null);
            s.peakFrom = os != null && os.peakFrom != null ? os.peakFrom : (ds != null ? ds.peakFrom : null);
            r.stages = s;
        }
        return r;
    }

    private static int orElse(Integer v, int def) {
        return v != null ? v : def;
    }

    private static double orElse(Double v, double def) {
        return v != null ? v : def;
    }

    // --- 既定値 ---

    private static final Json DEFAULT_JSON = buildDefaultJson();
    public static final PhaseTable DEFAULT = new PhaseTable(null);

    private static Json buildDefaultJson() {
        Json j = new Json();
        j.global = new Global();
        j.global.startWorldAgeTicks = 72000;
        j.global.startAdvancements = 6;
        j.global.points = new Points();
        // 2026-11-14 夕 ユーザー決定: 時間 12 秒で 1 点(1 日 100)・面積 毎秒 今の過繁茂のチャンク数 × 0.01・撃破は閾値の %(上限なし)。実績は【仮】0。
        j.global.points.perAdvancement = 0;
        j.global.points.timePerDay = 100.0D;
        j.global.points.areaPerChunkPerSecond = 0.01D;
        KillPoints kp = new KillPoints();
        kp.stage0 = 0.1D;
        kp.evolved = 0.3D;
        kp.g2 = 0.3D;
        kp.g3 = 0.6D;
        kp.g4 = 1.0D;
        j.global.points.kills = kp;
        // 前兆(2026-10-24 地脈技術の 4 段目。全部仮決め)。
        OmenJson o = new OmenJson();
        o.leadTicks = 12000;
        o.sampleLeadTicks = 24000;
        o.sampleChance = 0.05D;
        o.pumpJitter = 0.4D;
        o.pumpJitterTicks = 100;
        o.noticeTicks = 12000;
        o.noticeRadius = 32;
        o.quakeMinTicks = 12000;
        o.quakeMaxTicks = 24000;
        o.quakeMinDistance = 16;
        o.quakeMaxDistance = 48;
        o.quakeIntensity = 0.4D;
        o.quakeTicks = 100;
        o.flowerWiltChance = 0.1D;
        o.flowerRecoverChance = 0.15D;
        o.animalAvoidExtra = 6;
        j.global.omen = o;
        // 小さな段(2026-11-14 ユーザー決定は「3 つ」まで。倍率と区切りは【仮】)。
        Stages st = new Stages();
        st.intro = 0.3D;
        st.established = 0.7D;
        st.peak = 1.0D;
        st.establishedFrom = 1.0D / 3.0D;
        st.peakFrom = 2.0D / 3.0D;
        j.global.stages = st;

        // 昇格条件: ポイントのみ。閾値は【仮】2026-11-14 夜の計測の案(F2・F3 は実測、F4〜F7 は伸び方からの見積もり。撃破は入れずに、日数の表どおり F7 まで約 75 日)。
        //   日数の表: F1→F2 6 日・F2→F3 8・F3→F4 10・F4→F5 12・F5→F6 17・F6→F7 22(F7 まで 75 日)。
        //   F8〜F10 は【仮】前の値の比(F7 : F8 : F9 : F10 = 67.8 : 55.8 : 73.8 : 94.3 百万)を F7 の 7.77 億に掛けて丸めた。autoAdvance:false で止まる。
        //   (前の値 2026-09-28: 263000 / 696000 / 1421000 / 2452000 / 26329000 / 67841000 / 55816000 / 73784000 / 94305000)
        //   11-18 夜 ユーザー決定でフェーズの長さを役割で変えた(F1・F2 1〜3 日・F3 3〜5・F4 6〜8・F5 10〜20・F6 3〜6・F7 10〜20)。
        //   下はその真ん中(2・2・4・7・15・4.5・F7 15)に合わせて、同じ計測から出し直した値【仮】(上の 75 日の表と値は前のもの)。
        long[] pointsToAdvance = {0, 0, 22_000L, 90_000L, 700_000L, 5_200_000L, 56_000_000L, 36_000_000L,
                216_000_000L, 285_000_000L, 365_000_000L};
        // ふつうの広さ(今の過繁茂のチャンク数。司令塔のバイオマスの物差し)。2026-11-14 夜の計測(感染の入ったビルド・プレイヤーは汚染のそば・
        // フェーズは日数の表どおり)の、各フェーズの真ん中の日の台帳。F1・F2 は実測、F3 から先は伸び方からの見積もり【仮】。F8〜F10 は F7 と同じ【仮】。
        // 11-18 夜: 新しいフェーズの長さの真ん中の日の台帳に直した【仮】。
        int[] normalArea = {0, 900, 3_600, 14_000, 59_000, 300_000, 670_000, 1_190_000, 1_190_000, 1_190_000, 1_190_000};

        j.phases = new java.util.LinkedHashMap<>();
        for (int n = MIN_PHASE; n <= MAX_PHASE; n++) {
            Phase p = new Phase();
            p.pointsToAdvance = pointsToAdvance[n] > 0 ? Long.valueOf(pointsToAdvance[n]) : null;
            p.normalArea = normalArea[n];

            p.notes = "";
            j.phases.put(String.valueOf(n), p);
        }
        return j;
    }
}
