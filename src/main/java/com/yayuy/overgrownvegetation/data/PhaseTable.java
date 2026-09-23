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
 * — 既定ではフェーズ7以降がこれに当たり、自動進行はフェーズ6で止まる。
 *
 * <p>2026-09-15 に「過繫茂系チャンク数が一定に達したら昇格」({@code chunksToAdvance} /
 * {@code advanceMode})は<b>廃止した</b>。汚染の広がりは、時間経過ポイントの倍率
 * ({@code points.perTickPerOvergrownChunk})を通して<b>昇格を早める</b>形でだけ効く。
 *
 * <p>既定の条件: F2 = 360000 点 / F3 = 800000 点 / F4 = 1400000 点 / F5 = 2800000 点 /
 * F6 = 5800000 点(汚染が 20 チャンク/分で広がる実測ペースで F1→F6 がおよそゲーム内 22 日)。
 * フェーズ0→1 だけは別枠で、{@code global.startWorldAgeTicks}(既定 3 日)と
 * {@code global.startAdvancements}(既定 6 個の実績)の<b>両方</b>が条件。
 * 判定そのものは {@link #canAdvanceTo(int, long)} に集約してある。
 *
 * <p>2026-09-21 に、「どの種がどのフェーズのどこに湧くか」({@code spawnGroups} /
 * {@code outsideSpawns.types} / {@code carrierSpawns.types})は <b>{@code mobs.json} の
 * {@code spawns}</b> へ移した。ここに残っているのは外湧きの<b>枠</b>(頭数・確率・距離)と、
 * ワールドに同時に存在できる群れの数({@code maxPacks})。
 *
 * <p>フェーズの内容の既定値は、現行の散在ロジック
 * ({@code OvergrownBiomeSpawner.spawnPoolFor} / {@code Group1PhaseBehavior} /
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
    }

    /** ポイントの獲得条件。2026-09-13 の再編でこの 5 本だけになった。 */
    public static final class Points {
        /** 実績を 1 つ解除するたび。 */
        public Integer perAdvancement;
        /** 1 tick 経過するたび(小数可。既定 1.0 = 毎 tick 1 点)。 */
        public Double perTick;
        /**
         * 1 tick 経過するたびに、これ ×「そのワールドで過繫茂系バイオームになったチャンクの総数」も
         * 加算する(2026-09-15。既定 0.005 = 総チャンク 200 ごとに毎 tick +1 点)。汚染が広がるほど
         * 時間あたりの収入が増える仕組みで、1 分あたりの収入は {@code 1200 × (perTick + これ × 総チャンク数)}。
         * 0 で無効(従来の時間経過のみ)。
         */
        public Double perTickPerOvergrownChunk;
        /** グループ1〜10の Mob が 1 体死ぬたび(誰が倒したかは問わない)。 */
        public Integer perGroupMobDeath;
        /** グループ1〜10の Mob が他の Mob を 1 体倒すたび(グループ3がグループ2を捕食したぶんも含む)。 */
        public Integer perGroupMobKill;
        /** チャンクが 1 つ新たに過繁茂系バイオームになるたび。 */
        public Integer perOvergrownChunk;
    }

    /**
     * 過繫茂系バイオームの<b>外</b>へ少数だけ湧かせるときの「枠」(2026-09-19)。
     * 頭数の上限・確率・距離だけを持ち、<b>湧かせる種は {@code mobs.json} の {@code spawns.outside}</b>
     * 側にある(2026-09-21 に移動)。{@code phase/OutsideInvaderSpawner} が使う。
     */
    public static final class OutsideSpawns {
        /** プレイヤー周辺にこの数だけ居たら、それ以上は湧かせない(既定 2)。 */
        public Integer maxNearby;
        /** 判定 1 回・プレイヤー 1 人あたりに湧きを試す確率(既定 0.12)。 */
        public Double chance;
        /** プレイヤーからの最小距離(ブロック、既定 48)。 */
        public Integer minDistance;
        /** プレイヤーからの最大距離(ブロック、既定 96)。 */
        public Integer maxDistance;
    }

    /**
     * 小規模レイドの設定。{@code composition} は「種の id → 体数」(既定: アルボラ 8 + 他のグループ4 の
     * 移動できる種を 1 体ずつ)。間隔は前回の小規模レイドからの tick。
     */
    public static final class SmallRaid {
        /** このフェーズで小規模レイドが起きるか。 */
        public Boolean enabled;
        /** 前回からの最短間隔(tick)。 */
        public Integer minIntervalTicks;
        /** 前回からの最長間隔(tick)。 */
        public Integer maxIntervalTicks;
        /** 狙われたプレイヤーからの最小距離(ブロック)。 */
        public Integer minDistance;
        /** 狙われたプレイヤーからの最大距離(ブロック)。ロード済みチャンクの中に収まる値にすること。 */
        public Integer maxDistance;
        /** 湧かせる編成: 種の id(名前空間省略可)→ 体数。 */
        public java.util.Map<String, Integer> composition;
    }

    /** 混成の群れで湧かせるグループと、1 群れの体数。 */
    public static final class MixedPacks {
        /** 混成の群れで湧かせる MobGroup 番号(空 / null で無効)。 */
        public List<Integer> groups;
        /** 1 群れの最小体数。 */
        public Integer min;
        /** 1 群れの最大体数。 */
        public Integer max;
    }

    public static final class Group1 {
        public Boolean buffed;
        public Double healthMultiplier;
        public Double attackMultiplier;
        public Boolean fleeCorruption;
    }

    public static final class Phase {
        /**
         * 直前のフェーズ開始時点から、このフェーズへ進むのに必要な累計ポイント(null / 0 で条件なし
         * = そのフェーズへは自動昇格しない)。昇格条件はこれだけ
         * (チャンク数による昇格は 2026-09-15 に廃止)。
         */
        public Integer pointsToAdvance;
        /**
         * ワールドに同時に存在できる「群れ」の数(2026-09-21、0 / null = 無制限)。
         * 生態系スポーンが 1 か所にまとめて湧かせたひとかたまりを 1 群れと数える
         * ({@code phase/PackTracker})。上限に達している間は新しい群れを湧かせない。
         */
        public Integer maxPacks;
        /**
         * このフェーズで「混成の群れ」として湧かせるグループ(2026-09-15、既定はフェーズ6以降の
         * グループ4)。ここに入れたグループは種ごとの群れではなく、1 箇所に
         * {@code min}〜{@code max} 体の<b>種がばらばらな</b>群れとして湧く
         * ({@code OvergrownBiomeSpawner#spawnMixedPack})。
         */
        public MixedPacks mixedPacks;
        /**
         * 過繫茂系バイオームの<b>外</b>にも少数だけ湧かせる種(2026-09-19、既定はフェーズ2〜4 のアルボラ)。
         * 「過繫茂バイオームの中の生態系」とは別処理で、こちらは<b>その外</b>専用の枠。
         */
        public OutsideSpawns outsideSpawns;
        /**
         * 過繫茂系バイオームの<b>外</b>に湧かせる<b>媒介種(グループ1)</b>の枠(2026-09-19、既定はフェーズ2〜6)。
         * {@code outsideSpawns} と同じ形だが、頭数の上限を別に数える(拡大種のはぐれと媒介種が
         * 互いの枠を食い合わないように)。媒介種は湧いたあと普通の Mob を襲って胞子感染を広げ、
         * 死体から汚染シダを生やす = 「汚染を運ぶ」役。{@code phase/OutsideInvaderSpawner} が使う。
         */
        public OutsideSpawns carrierSpawns;
        /**
         * 小規模レイド(2026-09-19、既定はフェーズ3以降)。プレイヤーの遠くに拡大種の一団が湧き、
         * そのプレイヤーを目指して歩いてくる。{@code raid/SmallRaidData} が使う。
         */
        public SmallRaid smallRaid;
        /** レイドの増援ウェーブで湧く MobGroup 番号。{@code RaidWaveSpawner}。 */
        public List<Integer> raidWaveGroups;
        public Group1 group1;
        /** このフェーズでレイドイベントが発生しうるか。 */
        public Boolean raidEnabled;
        /**
         * このフェーズへ上がったときに攻城戦(ドルガ=アーケロウス・ティタニカ)を始めるか
         * (2026-09-17、既定はフェーズ7以降)。{@code SiegeData#onPhaseAdvanced} が見る。
         */
        public Boolean siegeEnabled;
        /** 自由記述の内容メモ(挙動には影響しない — マネージャー表示用)。 */
        public String notes;
    }

    // --- 解決済みの値 ---

    private final int startWorldAgeTicks;
    private final int startAdvancements;
    private final int pointsPerAdvancement;
    private final double pointsPerTick;
    private final double pointsPerTickPerOvergrownChunk;
    private final int pointsPerGroupMobDeath;
    private final int pointsPerGroupMobKill;
    private final int pointsPerOvergrownChunk;
    private final Phase[] phases; // index 1..10

    private PhaseTable(Json j) {
        Json d = DEFAULT_JSON;
        Global g = j != null && j.global != null ? j.global : new Global();
        Global dg = d.global;
        this.startWorldAgeTicks = orElse(g.startWorldAgeTicks, dg.startWorldAgeTicks);
        this.startAdvancements = orElse(g.startAdvancements, dg.startAdvancements);
        Points p = g.points != null ? g.points : new Points();
        Points dp = dg.points;
        this.pointsPerAdvancement = orElse(p.perAdvancement, dp.perAdvancement);
        this.pointsPerTick = orElse(p.perTick, dp.perTick);
        this.pointsPerTickPerOvergrownChunk =
                orElse(p.perTickPerOvergrownChunk, dp.perTickPerOvergrownChunk);
        this.pointsPerGroupMobDeath = orElse(p.perGroupMobDeath, dp.perGroupMobDeath);
        this.pointsPerGroupMobKill = orElse(p.perGroupMobKill, dp.perGroupMobKill);
        this.pointsPerOvergrownChunk = orElse(p.perOvergrownChunk, dp.perOvergrownChunk);

        this.phases = new Phase[MAX_PHASE + 1];
        for (int n = MIN_PHASE; n <= MAX_PHASE; n++) {
            Phase def = DEFAULT_JSON.phases.get(String.valueOf(n));
            Phase over = j != null && j.phases != null ? j.phases.get(String.valueOf(n)) : null;
            this.phases[n] = merge(def, over);
        }
    }

    static PhaseTable fromJson(Json j) {
        return new PhaseTable(j);
    }

    public int startWorldAgeTicks() {
        return startWorldAgeTicks;
    }

    /** フェーズ1が始まるのに必要な「解除済み実績の種類数」(0 で実績ゲートなし)。 */
    public int startAdvancements() {
        return startAdvancements;
    }

    public int pointsPerAdvancement() {
        return pointsPerAdvancement;
    }

    public double pointsPerTick() {
        return pointsPerTick;
    }

    /**
     * 1 tick あたり、これ × 過繫茂系バイオームの総チャンク数 が上乗せされる
     * ({@code points.perTickPerOvergrownChunk})。汚染が広がるほど時間収入が増える。
     */
    public double pointsPerTickPerOvergrownChunk() {
        return pointsPerTickPerOvergrownChunk;
    }

    /**
     * 過繫茂系バイオームの総チャンク数が {@code overgrownChunks} のときの、1 tick あたりの
     * 時間経過ポイント({@code perTick + perTickPerOvergrownChunk × 総チャンク数})。
     */
    public double pointsPerTickWithChunks(long overgrownChunks) {
        return pointsPerTick + pointsPerTickPerOvergrownChunk * Math.max(0L, overgrownChunks);
    }

    /** グループ1〜10の Mob 1 体の死亡で入るポイント。 */
    public int pointsPerGroupMobDeath() {
        return pointsPerGroupMobDeath;
    }

    /** グループ1〜10の Mob が他の Mob を 1 体倒したときに入るポイント。 */
    public int pointsPerGroupMobKill() {
        return pointsPerGroupMobKill;
    }

    /** チャンクが 1 つ新たに過繁茂系バイオームになったときに入るポイント。 */
    public int pointsPerOvergrownChunk() {
        return pointsPerOvergrownChunk;
    }

    /** {@code n} フェーズへ進むのに必要なポイント。条件が無ければ {@link Long#MAX_VALUE}。 */
    public long pointsToAdvance(int n) {
        if (!inAdvanceRange(n)) {
            return Long.MAX_VALUE;
        }
        Integer v = phases[n].pointsToAdvance;
        return v != null && v > 0 ? v : Long.MAX_VALUE;
    }

    /**
     * フェーズ {@code n} へ昇格できるか。判定はポイントだけ
     * (過繫茂系チャンク数による昇格は 2026-09-15 に廃止。チャンクは
     * {@link #pointsPerTickWithChunks} 経由でポイントの貯まる速さにだけ効く)。
     *
     * @param points 直前のフェーズ開始時点からの獲得ポイント
     * @return 条件を満たしていれば true。{@code pointsToAdvance} が設定されていないフェーズは常に false
     *         (= そこへは自動昇格しない)。
     */
    public boolean canAdvanceTo(int n, long points) {
        long need = pointsToAdvance(n);
        return need != Long.MAX_VALUE && points >= need;
    }

    /** 自動昇格の対象になりうるフェーズ番号か(フェーズ1は開始フェーズなので対象外)。 */
    private static boolean inAdvanceRange(int n) {
        return n >= MIN_PHASE + 1 && n <= MAX_PHASE;
    }

    public Phase phase(int n) {
        int clamped = Math.max(MIN_PHASE, Math.min(MAX_PHASE, n));
        return phases[clamped];
    }

    /**
     * このフェーズに、ワールドへ同時に存在できる「群れ」の数(0 / 未設定 = 無制限)。
     * 1 群れ = 生態系スポーンが 1 か所にまとめて湧かせたひとかたまり(1 体でも 1 群れ)。
     * 上限に達している間は<b>新しい群れを湧かせない</b>だけで、既にいる群れは消えない。
     *
     * @see com.yayuy.overgrownvegetation.phase.PackTracker
     */
    public int maxPacks(int n) {
        Integer v = phase(n).maxPacks;
        return v != null && v > 0 ? v : 0;
    }

    public List<Integer> raidWaveGroups(int n) {
        List<Integer> g = phase(n).raidWaveGroups;
        return g != null ? g : List.of();
    }

    /** 外湧きをプレイヤー周辺に許す最大数(既定 2)。 */
    public int outsideSpawnMaxNearby(int n) {
        OutsideSpawns o = phase(n).outsideSpawns;
        return o != null && o.maxNearby != null ? Math.max(0, o.maxNearby) : 2;
    }

    /** 外湧きの判定 1 回・プレイヤー 1 人あたりの確率(既定 0.12)。 */
    public double outsideSpawnChance(int n) {
        OutsideSpawns o = phase(n).outsideSpawns;
        return o != null && o.chance != null ? Math.max(0.0D, o.chance) : 0.12D;
    }

    /** 外湧きのプレイヤーからの最小距離(ブロック、既定 48)。 */
    public int outsideSpawnMinDistance(int n) {
        OutsideSpawns o = phase(n).outsideSpawns;
        return o != null && o.minDistance != null ? Math.max(1, o.minDistance) : 48;
    }

    /** 外湧きのプレイヤーからの最大距離(ブロック、既定 96。最小より小さければ最小に丸める)。 */
    public int outsideSpawnMaxDistance(int n) {
        OutsideSpawns o = phase(n).outsideSpawns;
        int max = o != null && o.maxDistance != null ? o.maxDistance : 96;
        return Math.max(outsideSpawnMinDistance(n), max);
    }

    /**
     * 外湧き 1 組の「枠」の解決済みの値。<b>湧かせる種は持たない</b> —— 2026-09-21 に
     * {@code mobs.json} の {@code spawns.outside} へ移したので、こちらは頭数・確率・距離だけ。
     */
    public record OutsideSpawnSettings(int maxNearby, double chance, int minDistance, int maxDistance) {
        public boolean enabled() {
            return maxNearby > 0 && chance > 0.0D;
        }
    }

    private static OutsideSpawnSettings resolve(OutsideSpawns o, int defMaxNearby, double defChance, int defMin, int defMax) {
        int maxNearby = o != null && o.maxNearby != null ? Math.max(0, o.maxNearby) : defMaxNearby;
        double chance = o != null && o.chance != null ? Math.max(0.0D, o.chance) : defChance;
        int min = o != null && o.minDistance != null ? Math.max(8, o.minDistance) : defMin;
        int max = o != null && o.maxDistance != null ? o.maxDistance : defMax;
        return new OutsideSpawnSettings(maxNearby, chance, min, Math.max(min, max));
    }

    /** 拡大種のはぐれ({@code outsideSpawns})。 */
    public OutsideSpawnSettings outsideSpawns(int n) {
        return resolve(phase(n).outsideSpawns, 2, 0.12D, 48, 96);
    }

    /**
     * 媒介種(グループ1)の外湧き({@code carrierSpawns}、2026-09-19)。既定はフェーズ2〜6 で
     * グループ1の全 6 種、プレイヤー周辺 4 体まで、確率 0.15、32〜80 ブロック。
     */
    public OutsideSpawnSettings carrierSpawns(int n) {
        return resolve(phase(n).carrierSpawns, 4, 0.15D, 32, 80);
    }

    // --- 小規模レイド(2026-09-19) ---

    public boolean smallRaidEnabled(int n) {
        SmallRaid s = phase(n).smallRaid;
        return s != null && Boolean.TRUE.equals(s.enabled);
    }

    /** 小規模レイドが起き始める最小フェーズ。無ければ {@link #MAX_PHASE}+1。 */
    public int smallRaidMinPhase() {
        for (int n = MIN_PHASE; n <= MAX_PHASE; n++) {
            if (smallRaidEnabled(n)) {
                return n;
            }
        }
        return MAX_PHASE + 1;
    }

    public int smallRaidMinIntervalTicks(int n) {
        SmallRaid s = phase(n).smallRaid;
        return s != null && s.minIntervalTicks != null ? Math.max(200, s.minIntervalTicks) : 36000;
    }

    public int smallRaidMaxIntervalTicks(int n) {
        SmallRaid s = phase(n).smallRaid;
        int max = s != null && s.maxIntervalTicks != null ? s.maxIntervalTicks : 72000;
        return Math.max(smallRaidMinIntervalTicks(n), max);
    }

    public int smallRaidMinDistance(int n) {
        SmallRaid s = phase(n).smallRaid;
        return s != null && s.minDistance != null ? Math.max(16, s.minDistance) : 96;
    }

    public int smallRaidMaxDistance(int n) {
        SmallRaid s = phase(n).smallRaid;
        int max = s != null && s.maxDistance != null ? s.maxDistance : 144;
        return Math.max(smallRaidMinDistance(n), max);
    }

    /** 小規模レイドの編成(種の id → 体数)。未設定なら既定の編成。 */
    public java.util.Map<String, Integer> smallRaidComposition(int n) {
        SmallRaid s = phase(n).smallRaid;
        return s != null && s.composition != null && !s.composition.isEmpty()
                ? s.composition : DEFAULT_SMALL_RAID_COMPOSITION;
    }

    /** 既定の編成: アルボラ 8 + 他のグループ4 の動ける種 1 体ずつ(ユーザー指定 2026-09-19)。 */
    private static final java.util.Map<String, Integer> DEFAULT_SMALL_RAID_COMPOSITION;
    static {
        java.util.LinkedHashMap<String, Integer> m = new java.util.LinkedHashMap<>();
        m.put("arbora", 8);
        m.put("phyllosens", 1);
        m.put("velophyll", 1);
        m.put("sporophyll", 1);
        m.put("overgrown_shielder", 1);
        DEFAULT_SMALL_RAID_COMPOSITION = java.util.Collections.unmodifiableMap(m);
    }

    /**
     * このフェーズで「混成の群れ」として湧かせるグループ番号(空なら従来どおり種ごとの群れ)。
     * 既定ではフェーズ6以降のグループ4。
     */
    public List<Integer> mixedPackGroups(int n) {
        MixedPacks mp = phase(n).mixedPacks;
        return mp != null && mp.groups != null ? mp.groups : List.of();
    }

    /** 混成の群れ 1 つの最小体数(既定 3)。 */
    public int mixedPackMin(int n) {
        MixedPacks mp = phase(n).mixedPacks;
        return mp != null && mp.min != null ? Math.max(1, mp.min) : 3;
    }

    /** 混成の群れ 1 つの最大体数(既定 5。最小より小さければ最小に丸める)。 */
    public int mixedPackMax(int n) {
        MixedPacks mp = phase(n).mixedPacks;
        int max = mp != null && mp.max != null ? mp.max : 5;
        return Math.max(mixedPackMin(n), max);
    }

    /** {@code groupNumber} がこのフェーズで混成の群れとして湧くグループか。 */
    public boolean isMixedPackGroup(int n, int groupNumber) {
        return mixedPackGroups(n).contains(groupNumber);
    }

    /** このフェーズへ上がったときに攻城戦が始まるか({@code siegeEnabled})。 */
    public boolean siegeEnabled(int n) {
        return Boolean.TRUE.equals(phase(n).siegeEnabled);
    }

    /** 攻城戦が始まる最小フェーズ(siegeEnabled が true の最小 n)。無ければ {@link #MAX_PHASE}+1。 */
    public int siegeMinPhase() {
        for (int n = MIN_PHASE; n <= MAX_PHASE; n++) {
            if (Boolean.TRUE.equals(phases[n].siegeEnabled)) {
                return n;
            }
        }
        return MAX_PHASE + 1;
    }

    /** レイドが発生し始める最小フェーズ(raidEnabled が true の最小 n)。無ければ {@link #MAX_PHASE}+1。 */
    public int raidMinPhase() {
        for (int n = MIN_PHASE; n <= MAX_PHASE; n++) {
            if (Boolean.TRUE.equals(phases[n].raidEnabled)) {
                return n;
            }
        }
        return MAX_PHASE + 1;
    }

    public boolean group1Buffed(int n) {
        Group1 g1 = phase(n).group1;
        return g1 != null && Boolean.TRUE.equals(g1.buffed);
    }

    public double group1HealthMultiplier(int n) {
        Group1 g1 = phase(n).group1;
        return g1 != null && g1.healthMultiplier != null ? g1.healthMultiplier : 1.0D;
    }

    public double group1AttackMultiplier(int n) {
        Group1 g1 = phase(n).group1;
        return g1 != null && g1.attackMultiplier != null ? g1.attackMultiplier : 1.0D;
    }

    public boolean group1FleeCorruption(int n) {
        Group1 g1 = phase(n).group1;
        return g1 != null && Boolean.TRUE.equals(g1.fleeCorruption);
    }

    // --- merge helpers ---

    private static Phase merge(Phase def, Phase over) {
        Phase r = new Phase();
        r.pointsToAdvance = over != null && over.pointsToAdvance != null ? over.pointsToAdvance : def.pointsToAdvance;
        r.maxPacks = over != null && over.maxPacks != null ? over.maxPacks : def.maxPacks;
        MixedPacks dmp = def.mixedPacks;
        MixedPacks omp = over != null ? over.mixedPacks : null;
        if (dmp != null || omp != null) {
            MixedPacks mp = new MixedPacks();
            mp.groups = omp != null && omp.groups != null ? omp.groups : (dmp != null ? dmp.groups : null);
            mp.min = omp != null && omp.min != null ? omp.min : (dmp != null ? dmp.min : null);
            mp.max = omp != null && omp.max != null ? omp.max : (dmp != null ? dmp.max : null);
            r.mixedPacks = mp;
        }
        r.outsideSpawns = mergeOutside(def.outsideSpawns, over != null ? over.outsideSpawns : null);
        r.carrierSpawns = mergeOutside(def.carrierSpawns, over != null ? over.carrierSpawns : null);
        SmallRaid dsr = def.smallRaid;
        SmallRaid osr = over != null ? over.smallRaid : null;
        if (dsr != null || osr != null) {
            SmallRaid s = new SmallRaid();
            s.enabled = osr != null && osr.enabled != null ? osr.enabled : (dsr != null ? dsr.enabled : null);
            s.minIntervalTicks = osr != null && osr.minIntervalTicks != null ? osr.minIntervalTicks : (dsr != null ? dsr.minIntervalTicks : null);
            s.maxIntervalTicks = osr != null && osr.maxIntervalTicks != null ? osr.maxIntervalTicks : (dsr != null ? dsr.maxIntervalTicks : null);
            s.minDistance = osr != null && osr.minDistance != null ? osr.minDistance : (dsr != null ? dsr.minDistance : null);
            s.maxDistance = osr != null && osr.maxDistance != null ? osr.maxDistance : (dsr != null ? dsr.maxDistance : null);
            s.composition = osr != null && osr.composition != null ? osr.composition : (dsr != null ? dsr.composition : null);
            r.smallRaid = s;
        }
        r.raidWaveGroups = over != null && over.raidWaveGroups != null ? over.raidWaveGroups : def.raidWaveGroups;
        r.raidEnabled = over != null && over.raidEnabled != null ? over.raidEnabled : def.raidEnabled;
        r.siegeEnabled = over != null && over.siegeEnabled != null ? over.siegeEnabled : def.siegeEnabled;
        r.notes = over != null && over.notes != null ? over.notes : def.notes;
        Group1 dg = def.group1 != null ? def.group1 : new Group1();
        Group1 og = over != null ? over.group1 : null;
        Group1 g = new Group1();
        g.buffed = og != null && og.buffed != null ? og.buffed : dg.buffed;
        g.healthMultiplier = og != null && og.healthMultiplier != null ? og.healthMultiplier : dg.healthMultiplier;
        g.attackMultiplier = og != null && og.attackMultiplier != null ? og.attackMultiplier : dg.attackMultiplier;
        g.fleeCorruption = og != null && og.fleeCorruption != null ? og.fleeCorruption : dg.fleeCorruption;
        r.group1 = g;
        return r;
    }

    @javax.annotation.Nullable
    private static OutsideSpawns mergeOutside(@javax.annotation.Nullable OutsideSpawns dos, @javax.annotation.Nullable OutsideSpawns oos) {
        if (dos == null && oos == null) {
            return null;
        }
        OutsideSpawns o = new OutsideSpawns();
        o.maxNearby = oos != null && oos.maxNearby != null ? oos.maxNearby : (dos != null ? dos.maxNearby : null);
        o.chance = oos != null && oos.chance != null ? oos.chance : (dos != null ? dos.chance : null);
        o.minDistance = oos != null && oos.minDistance != null ? oos.minDistance : (dos != null ? dos.minDistance : null);
        o.maxDistance = oos != null && oos.maxDistance != null ? oos.maxDistance : (dos != null ? dos.maxDistance : null);
        return o;
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
        j.global.points.perAdvancement = 100;
        j.global.points.perTick = 1.0D; // 毎 tick 1 点
        j.global.points.perTickPerOvergrownChunk = 0.005D; // + 毎 tick 「総チャンク数 × 0.005」ぶん(200ch で毎 tick +1)
        j.global.points.perGroupMobDeath = 1;
        j.global.points.perGroupMobKill = 1;
        j.global.points.perOvergrownChunk = 5;

        // 昇格条件(2026-09-15): ポイントのみ(チャンク数による昇格は廃止)。
        //   F2 = 360000 / F3 = 800000 / F4 = 1400000 / F5 = 2800000 / F6 = 5800000
        //   (F1→F6 でおよそゲーム内 64 日。汚染を積極的に広げれば 40 日ほど)
        //   フェーズ7以降 = 条件なし(内容が未実装なので、自動進行はフェーズ6で止まる)
        int[] pointsToAdvance = {0, 0, 360_000, 800_000, 1_400_000, 2_800_000, 5_800_000, 0, 0, 0, 0};

        j.phases = new java.util.LinkedHashMap<>();
        for (int n = MIN_PHASE; n <= MAX_PHASE; n++) {
            Phase p = new Phase();
            p.pointsToAdvance = pointsToAdvance[n] > 0 ? pointsToAdvance[n] : null;

            // フェーズ2〜4 は、過繫茂バイオームの外にもアルボラが少数だけ現れる(2026-09-19)。
            // 汚染がまだ狭いうちから「はぐれの拡大種」を見かけるようにするための枠。
            if (n >= 2 && n <= 4) {
                OutsideSpawns outside = new OutsideSpawns();
                outside.maxNearby = 2;
                outside.chance = 0.12D;
                outside.minDistance = 48;
                outside.maxDistance = 96;
                p.outsideSpawns = outside;
            }

            // フェーズ2〜6 は、媒介種(グループ1)が過繫茂バイオームの外にも湧く(2026-09-19)。
            // 普通の Mob を襲って胞子感染を広げ、死体から汚染シダを生やす = 汚染を運ぶ役。
            if (n >= 2 && n <= 6) {
                OutsideSpawns carrier = new OutsideSpawns();
                carrier.maxNearby = 4;
                carrier.chance = 0.15D;
                carrier.minDistance = 32;
                carrier.maxDistance = 80;
                p.carrierSpawns = carrier;
            }

            // フェーズ3以降、小規模レイド(遠くに湧いた一団がプレイヤーを襲いに来る。2026-09-19)。
            if (n >= 3) {
                SmallRaid sr = new SmallRaid();
                sr.enabled = true;
                sr.minIntervalTicks = 36000;
                sr.maxIntervalTicks = 72000;
                sr.minDistance = 96;
                sr.maxDistance = 144;
                sr.composition = DEFAULT_SMALL_RAID_COMPOSITION;
                p.smallRaid = sr;
            }

            // フェーズ6以降、グループ4は「種がばらばらな 3〜5 体の群れ」で湧く(2026-09-15)。
            if (n >= 6) {
                MixedPacks mp = new MixedPacks();
                mp.groups = List.of(4);
                mp.min = 3;
                mp.max = 5;
                p.mixedPacks = mp;
            }

            // RaidWaveSpawner.poolFor: group1 常時 + group2 が phase>=3 + group3 が phase>=4。
            List<Integer> wave = new ArrayList<>(List.of(1));
            if (n >= 3) {
                wave.add(2);
            }
            if (n >= 4) {
                wave.add(3);
            }
            if (n >= 5) {
                wave.add(4);
            }
            p.raidWaveGroups = wave;

            Group1 g1 = new Group1();
            g1.buffed = n >= 2;
            g1.healthMultiplier = 2.0D;
            g1.attackMultiplier = 1.5D;
            g1.fleeCorruption = n >= 2;
            p.group1 = g1;

            p.raidEnabled = n >= 5;
            // フェーズ7以降は、上がった瞬間に攻城戦(遠距離ボス)が始まる(2026-09-17、内容は仮)。
            p.siegeEnabled = n >= 7;
            p.notes = "";
            j.phases.put(String.valueOf(n), p);
        }
        return j;
    }
}
