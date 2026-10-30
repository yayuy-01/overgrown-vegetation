package com.yayuy.overgrownvegetation.td;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@code td.json} — TD イベント(森の侵攻)の設計値(2026-09-25)。仕組みは {@link TdTicker} / {@link TdData}。
 * 読み込みは {@code data/DesignData#td()}(同梱の json・{@code run/config} の写し・この Java の既定値の 3 か所を揃える)。
 *
 * <p><b>ユーザーが決めたこと(2026-09-24)</b>: 森は自動で攻めてくる・狙うのはターゲットブロック・攻め手の総数 = バイオマス
 * (1 体の値段は {@code mobs.json} の {@code tdCost}、既定 1 = 1 バイオマス 1 体。2026-09-25 ユーザー指定で種ごとに変えられる)・
 * 同時に出る実体には上限・遠くの群れはデータだけで行進する・囲えば壁を壊す・ターゲットが壊れたら負けで
 * 拠点の周りに汚染が噴き出す。<b>ユーザーが受け入れた仮の数値</b>: 開始はバイオマス 2000 以上・フェーズ 5 以上・
 * ターゲットから 192 ブロック以内に過繁茂チャンク、開始時にバイオマスを全部予算へ、同時上限 200(2026-09-25 のユーザー確認で 300 へ)。
 *
 * <p><b>それ以外の数値はすべて仮決め 2026-09-25</b>(TD 設計 v2 に書いた値と、実装で足した値)。
 *
 * <p>{@code /ov design reload} で即反映(進行中のイベントも次の判定から新しい値を使う。置いてあるターゲットの最大耐久だけは
 * {@code TargetBlockEntity#resetHp} まで変わらない)。
 */
public final class TdTable {

    // --- json の形 ---

    public static final class Json {
        public Double startBiomass;
        public Double overgrownSearchRadius;
        public Integer warningTicks;
        public Integer cooldownTicks;
        public Double pauseWithoutPlayersRadius;
        public Integer abandonTicks;
        public Integer concurrentCap;
        public Integer maxVirtualSwarms;
        public Integer swarmSpawnIntervalTicks;
        public Integer virtualReserve;
        public Double virtualSpawnMin;
        public Double virtualSpawnMax;
        public Double virtualSpawnBeyondSight;
        public Double virtualSpawnFarthest;
        public Double directionalShare;
        public Double directionalSpread;
        public Double directionalOvergrownBias;
        public Double materializeRadius;
        public Double playerMaterializeRadius;
        public Double airSwarmShare;
        public Double groundSpeedFactor;
        public Double airSpeed;
        public Integer strayDiscardTicks;
        public Double presentationRadius;
        public Double bossBarRadius;
        public Double defenseSuppressRadius;
        public Double targetMaxHp;
        public Double breakHardnessFactor;
        public SiegeDamageJson siegeDamage;
        public Integer defeatCorruptionRadius;
        public Double defeatCorruptionNoise;
        public Double climberShare;
        public Double hostileClearRadius;
        public Integer maxAirAttackers;
        public Double otherBreakTimeMultiplier;
        public Double corruptedBreakTimeMultiplier;
        public Integer bridgeWidth;
        /** グループ番号("5"〜"9")→ 地上の群れ 1 つに付く護衛の確率。 */
        public Map<String, Double> escortChance;
        /** 護衛に出さない種の id(overgrown_vegetation: は省略可)。 */
        public List<String> escortExcluded;
    }

    /** 役割ごとの 1 回の攻撃で塊・壁を削る量。前衛(FRONT)は攻撃力の属性を使うので無い。 */
    public static final class SiegeDamageJson {
        public Double support;
        public Double rangedScout;
    }

    // --- 値 ---

    private final double startBiomass;
    private final double overgrownSearchRadius;
    private final int warningTicks;
    private final int cooldownTicks;
    private final double pauseWithoutPlayersRadius;
    private final int abandonTicks;
    private final int concurrentCap;
    private final int maxVirtualSwarms;
    private final int swarmSpawnIntervalTicks;
    private final int virtualReserve;
    private final double virtualSpawnMin;
    private final double virtualSpawnMax;
    private final double virtualSpawnBeyondSight;
    private final double virtualSpawnFarthest;
    private final double directionalShare;
    private final double directionalSpread;
    private final double directionalOvergrownBias;
    private final double materializeRadius;
    private final double playerMaterializeRadius;
    private final double airSwarmShare;
    private final double groundSpeedFactor;
    private final double airSpeed;
    private final int strayDiscardTicks;
    private final double presentationRadius;
    private final double bossBarRadius;
    private final double defenseSuppressRadius;
    private final float targetMaxHp;
    private final float breakHardnessFactor;
    private final float siegeDamageSupport;
    private final float siegeDamageRangedScout;
    private final int defeatCorruptionRadius;
    private final double defeatCorruptionNoise;
    private final double climberShare;
    private final double hostileClearRadius;
    private final int maxAirAttackers;
    private final double otherBreakTimeMultiplier;
    private final double corruptedBreakTimeMultiplier;
    private final int bridgeWidth;
    /** 添え字 = グループ番号({@value #ESCORT_MIN_GROUP}〜{@value #ESCORT_MAX_GROUP})。それ以外は 0。 */
    private final double[] escortChance = new double[ESCORT_MAX_GROUP + 1];
    private final Set<ResourceLocation> escortExcluded;

    private TdTable(@Nullable Json j) {
        Json src = j != null ? j : new Json();
        SiegeDamageJson dmg = src.siegeDamage != null ? src.siegeDamage : new SiegeDamageJson();

        this.startBiomass = clampDouble(src.startBiomass, 2000.0D, 0.0D, 1.0e9D);
        this.overgrownSearchRadius = clampDouble(src.overgrownSearchRadius, 192.0D, 0.0D, 1024.0D);
        this.warningTicks = clampInt(src.warningTicks, 1200, 0, 72000);
        this.cooldownTicks = clampInt(src.cooldownTicks, 72000, 0, 24000 * 100);
        this.pauseWithoutPlayersRadius = clampDouble(src.pauseWithoutPlayersRadius, 128.0D, 8.0D, 1024.0D);
        this.abandonTicks = clampInt(src.abandonTicks, 72000, 20, 24000 * 100);
        this.concurrentCap = clampInt(src.concurrentCap, 500, 1, 2000);
        this.maxVirtualSwarms = clampInt(src.maxVirtualSwarms, 40, 1, 500);
        this.swarmSpawnIntervalTicks = clampInt(src.swarmSpawnIntervalTicks, 20, 1, 72000);
        this.virtualReserve = clampInt(src.virtualReserve, 150, 0, 5000);
        this.virtualSpawnMin = clampDouble(src.virtualSpawnMin, 128.0D, 16.0D, 1024.0D);
        this.virtualSpawnMax = Math.max(this.virtualSpawnMin,
                clampDouble(src.virtualSpawnMax, 192.0D, 16.0D, 1024.0D));
        this.virtualSpawnBeyondSight = clampDouble(src.virtualSpawnBeyondSight, 16.0D, -1.0D, 256.0D);
        this.virtualSpawnFarthest = Math.max(this.virtualSpawnMax,
                clampDouble(src.virtualSpawnFarthest, 480.0D, 16.0D, 1024.0D));
        this.directionalShare = clampDouble(src.directionalShare, 0.6D, 0.0D, 1.0D);
        this.directionalSpread = clampDouble(src.directionalSpread, 30.0D, 1.0D, 180.0D);
        this.directionalOvergrownBias = clampDouble(src.directionalOvergrownBias, 0.75D, 0.0D, 1.0D);
        this.materializeRadius = clampDouble(src.materializeRadius, 96.0D, 8.0D, 512.0D);
        this.playerMaterializeRadius = clampDouble(src.playerMaterializeRadius, 48.0D, 0.0D, 512.0D);
        this.airSwarmShare = clampDouble(src.airSwarmShare, 0.1D, 0.0D, 1.0D);
        this.groundSpeedFactor = clampDouble(src.groundSpeedFactor, 1.0D, 0.01D, 10.0D);
        this.airSpeed = clampDouble(src.airSpeed, 0.5D, 0.01D, 10.0D);
        this.strayDiscardTicks = clampInt(src.strayDiscardTicks, 1200, 20, 72000);
        this.presentationRadius = clampDouble(src.presentationRadius, 160.0D, 0.0D, 512.0D);
        this.bossBarRadius = clampDouble(src.bossBarRadius, 192.0D, 0.0D, 1024.0D);
        this.defenseSuppressRadius = clampDouble(src.defenseSuppressRadius, 256.0D, 0.0D, 2048.0D);
        this.targetMaxHp = (float) clampDouble(src.targetMaxHp, 1000.0D, 1.0D, 1.0e7D);
        this.breakHardnessFactor = (float) clampDouble(src.breakHardnessFactor, 10.0D, 0.0D, 1.0e4D);
        this.siegeDamageSupport = (float) clampDouble(dmg.support, 2.0D, 0.0D, 1.0e4D);
        this.siegeDamageRangedScout = (float) clampDouble(dmg.rangedScout, 0.0D, 0.0D, 1.0e4D);
        this.defeatCorruptionRadius = clampInt(src.defeatCorruptionRadius, 24, 0, 64);
        this.defeatCorruptionNoise = clampDouble(src.defeatCorruptionNoise, 2.0D, 0.0D, 16.0D);
        this.climberShare = clampDouble(src.climberShare, 0.35D, 0.0D, 1.0D);
        this.hostileClearRadius = clampDouble(src.hostileClearRadius, 0.0D, -1.0D, 2048.0D);
        this.maxAirAttackers = clampInt(src.maxAirAttackers, 20, 0, 2000);
        this.otherBreakTimeMultiplier = clampDouble(src.otherBreakTimeMultiplier, 5.0D, 1.0D, 1000.0D);
        this.corruptedBreakTimeMultiplier = clampDouble(src.corruptedBreakTimeMultiplier, 0.25D, 0.0D, 1.0D);
        this.bridgeWidth = clampInt(src.bridgeWidth, 4, 3, 5);
        Map<String, Double> escorts = src.escortChance != null ? src.escortChance : DEFAULT_ESCORT_CHANCE;
        for (int g = ESCORT_MIN_GROUP; g <= ESCORT_MAX_GROUP; g++) {
            this.escortChance[g] = clampDouble(escorts.get(Integer.toString(g)), 0.0D, 0.0D, 1.0D);
        }
        Set<ResourceLocation> excluded = new TreeSet<>();
        for (String id : src.escortExcluded != null ? src.escortExcluded : DEFAULT_ESCORT_EXCLUDED) {
            ResourceLocation rl = id == null ? null
                    : ResourceLocation.tryParse(id.contains(":") ? id : OvergrownVegetation.MODID + ":" + id);
            if (rl != null) {
                excluded.add(rl);
            }
        }
        this.escortExcluded = Collections.unmodifiableSet(excluded);
    }

    /** 護衛を付けるグループの範囲(ユーザー指定 2026-09-25「TD で G4〜9 も湧くように」。G4 は群れの編成、G5〜9 が護衛)。 */
    public static final int ESCORT_MIN_GROUP = 5;
    public static final int ESCORT_MAX_GROUP = 9;

    /** 護衛の確率の既定値(仮決め 2026-09-25。番号が上がるほど強いので少なく)。 */
    private static final Map<String, Double> DEFAULT_ESCORT_CHANCE = Map.of(
            "5", 0.25D, "6", 0.15D, "7", 0.1D, "8", 0.1D, "9", 0.05D);

    /**
     * 護衛に出さない種の既定値(td.json に {@code escortExcluded} が無いとき)。仮決め 2026-10-01: G5 の水中・地中・洞窟の 4 種
     * (地上の群れの護衛に選ばれて陸に置かれるため)。td.json(design と run/config)と揃える。
     */
    private static final List<String> DEFAULT_ESCORT_EXCLUDED = List.of("bathymnes", "erebomnes", "voramnes", "fodimnes");

    private static int clampInt(@Nullable Integer v, int def, int min, int max) {
        return v == null ? def : Math.max(min, Math.min(max, v));
    }

    private static double clampDouble(@Nullable Double v, double def, double min, double max) {
        return v == null || !Double.isFinite(v) ? def : Math.max(min, Math.min(max, v));
    }

    // --- 始まり方(ユーザーが受け入れた仮の数値 2026-09-24) ---

    // どのフェーズで侵攻が起きるかは phases.json の tdEnabled(2026-09-29 に minPhase を廃止して移した。
    // レイド・ティタニカ戦と同じく、mod マネージャーのフェーズタブで 1 つずつ入り切りできる)。

    /** 世界のバイオマスがこれ以上で始まる(ユーザーが受け入れた仮の数値: 2000)。 */
    public double startBiomass() {
        return startBiomass;
    }

    /** ターゲットからこの距離(ブロック)以内に過繁茂チャンクがあれば狙われる(ユーザーが受け入れた仮の数値: 192)。 */
    public double overgrownSearchRadius() {
        return overgrownSearchRadius;
    }

    /** 予告(WARNING)から侵攻(ACTIVE)までの時間(tick)。仮決め 2026-09-25: 1200(1 分)。 */
    public int warningTicks() {
        return warningTicks;
    }

    /** 1 回の侵攻が終わってから次に起こせるまで(tick)。仮決め 2026-09-25: 72000(3 日)。 */
    public int cooldownTicks() {
        return cooldownTicks;
    }

    // --- プレイヤーがいないとき ---

    /**
     * ターゲットからこの距離に観戦者でないプレイヤーが 1 人もいなければ<b>増援</b>を一時停止する(ブロック)。仮決め 2026-09-25: 128。
     * 拠点にいる攻め手の攻城(削り・負け)は止めない(レビュー指摘 2026-09-25。{@code TdEvents#isSiegeLive})。
     */
    public double pauseWithoutPlayersRadius() {
        return pauseWithoutPlayersRadius;
    }

    /** 一時停止がこれだけ続いたら侵攻を取りやめて払い戻す(tick)。仮決め 2026-09-25: 72000(3 日)。 */
    public int abandonTicks() {
        return abandonTicks;
    }

    // --- 群れ ---

    /**
     * 同時に出ている実体の上限。数えるのは<b>読み込まれている</b>攻め手(読み込まれていない攻め手は重さにならない)。
     * 仮想の群れはこれを超えない間だけ実体になる({@code TdTicker#materialize})。
     * はじめはユーザーが受け入れた仮の数値 200。2026-09-25 ユーザー「処理に余裕があるので 400〜500 体ほどまで規模を上げてよい」
     * (その数は {@code /kill} の数など、落ちた物・汚染シダ・村人も入った数)→ 300(仮決め)。
     * 2026-09-25 のユーザー報告(runClient 3 回目)「TD であるのに物量が足りなく見える。mob の上限を 500 とし、1 体死ねば 1 体補充
     * されるくらいでもよい」→ <b>500(ユーザー指定)</b>。補充の速さは {@link #virtualReserve}。
     */
    public int concurrentCap() {
        return concurrentCap;
    }

    /** 同時に行進する(待つ輪で待っているものも含む)仮想の群れの数の上限。仮決め 2026-09-25: 30 → 40(控え {@link #virtualReserve} のぶん)。 */
    public int maxVirtualSwarms() {
        return maxVirtualSwarms;
    }

    /**
     * 仮想の群れを作る間隔(tick)。1 回に、行進中の頭数が「上限の空き」と「控え」の大きい方に届くまで何群れでも作る
     * (はじめは 1 回に 1 群れで、上限に関係なく 1 分に約 72 体しか出なかった。レビュー指摘 2026-09-25)。
     * 仮決め 2026-09-25: 100 → 20(ユーザー報告「補充をもっと早く」。帳簿のたびに作る)。
     */
    public int swarmSpawnIntervalTicks() {
        return swarmSpawnIntervalTicks;
    }

    /**
     * 行進中の仮想の群れの頭数を、上限が埋まっていてもこれだけは持っておく(控え。待つ輪 = 実体化の距離の少し外で待つ)。
     * 2026-09-25 のユーザー報告(runClient 3 回目)「mob の補充をもっと早くできないか。1 体死ねば 1 体補充されるくらいでもよい」:
     * それまでは「読み込まれている攻め手 + 行進中」が上限に届くと群れを作らなかったので、倒されてから遠い輪(128〜192)で次の群れが
     * 生まれ、待つ輪まで 5〜28 秒歩いてから実体になっていた。控えがいれば、倒された分は次の帳簿(1 秒以内)で待つ輪の群れが実体になる
     * (1 群れの頭数ぶん空いたら。上限は超えない)。遠景の身代わりの大群としても見える。仮決め 150。0 = 控えを持たない(前の流れ)。
     */
    public int virtualReserve() {
        return virtualReserve;
    }

    /** 仮想の群れが生まれる、ターゲットからの距離の下限・上限(ブロック)。仮決め 2026-09-25: 128〜192。 */
    public double virtualSpawnMin() {
        return virtualSpawnMin;
    }

    public double virtualSpawnMax() {
        return virtualSpawnMax;
    }

    /**
     * 仮想の群れを、近くのプレイヤーの<b>見える範囲の外</b>(霧の終わり・描画距離。クライアントが知らせる {@link TdSight})から
     * さらにこれ〜2 倍だけ外で生ませる(ブロック)。{@link #virtualSpawnMin}〜{@link #virtualSpawnMax} の輪(始めの一斉投入は
     * 実体化の輪)が誰かの見える範囲に入るときだけ外へ押し出す。2026-09-26 のユーザー指定「TD で mob が絵として湧く場所をもっと遠くから。
     * 外周の霧の中から出てくる感じに」: それまでは描画距離が 12 チャンクなら輪の群れが見えている所に急に現れ、始めの一斉投入は
     * 92〜95 ブロックに一度に現れていた。16 は仮決め。負の数 = 押し出さない(前の輪のまま)。
     */
    public double virtualSpawnBeyondSight() {
        return virtualSpawnBeyondSight;
    }

    /**
     * 見える範囲の外へ押し出すときの、ターゲットからの距離の上限(ブロック)。描画距離がとても長いと行進が長くなりすぎるので止める
     * (その先は見えている所に現れる)。群れを送るのはプレイヤーから 512 まで。仮決め 2026-09-26: 480。
     */
    public double virtualSpawnFarthest() {
        return virtualSpawnFarthest;
    }

    /**
     * 侵攻が<b>一方向から</b>攻めてくる割合(残りは全方位)。侵攻に入るときに振る({@code TdTicker#rollDirection})。
     * ユーザー指定 2026-09-26「大体全周囲 4 割、特定の方向からが 6 割くらい」→ 0.6。
     */
    public double directionalShare() {
        return directionalShare;
    }

    /**
     * 一方向の侵攻で、群れが生まれる扇の<b>半分の角度</b>(度)。向き ± これの中で、行進中の群れの向きのいちばん大きな隙間を選ぶ。
     * 仮決め 2026-09-26: 30(扇の幅 60 度。待つ輪 92 では幅約 95 ブロックの前線)。
     */
    public double directionalSpread() {
        return directionalSpread;
    }

    /**
     * 一方向の侵攻の向きを、過繁茂チャンクの多い向きへ寄せる強さ(0〜1)。8 方位のそれぞれの確率 =
     * この値 × その向きの過繁茂チャンクの割合 + (1 − この値) ÷ 8(ユーザー指定 2026-09-26「過繁茂したバイオームがある方向からに
     * なりやすいように」。数えるのはターゲットから {@link #overgrownSearchRadius} の中)。過繁茂チャンクが無ければ 8 方位から等しく。
     * 仮決め 2026-09-26: 0.75(過繁茂が 1 方向だけなら約 78% でその向き)。
     */
    public double directionalOvergrownBias() {
        return directionalOvergrownBias;
    }

    /**
     * 仮想の群れがターゲットからこの距離まで来たら実体になる(ブロック、水平)。はじめは仮決め 64。
     * 2026-09-25 ユーザー「mob に切り替える範囲が狭いのでもう少し広げてよい」→ 96(仮決め)。アルボラはバニラが約 63 ブロックより先を
     * 描かないので、実体になってしばらくは 1 段目の身代わり(絵)で描かれる —— 絵から絵へ入れ替わるので目立たない。
     */
    public double materializeRadius() {
        return materializeRadius;
    }

    /** 仮想の群れからこの距離にプレイヤーがいれば、ターゲットから遠くても実体になる(ブロック)。仮決め 2026-09-25: 48。 */
    public double playerMaterializeRadius() {
        return playerMaterializeRadius;
    }

    /**
     * 仮想の群れを空の群れ(ヴェロフィル)にする割合。はじめは仮決め 0.2。2026-09-25 ユーザー報告「ヴェロフィルの湧き量が多すぎる」
     * → 0.1(仮決め)。上限は {@link #maxAirAttackers}。
     */
    public double airSwarmShare() {
        return airSwarmShare;
    }

    /**
     * 同時に出ているヴェロフィル(読み込まれている攻め手 + 行進中の空の群れ)の上限。届いていれば空の群れを作らない。
     * 2026-09-25 ユーザー報告「ヴェロフィルの湧き量が多すぎる」: ヴェロフィルはターゲットを削らない(攻城の削りが 0)ので、
     * 倒されない限り残り続け、割合より多く溜まって同時の上限を埋めていた。20 は仮決め。0 = 空の群れを出さない。
     */
    public int maxAirAttackers() {
        return maxAirAttackers;
    }

    /**
     * 侵攻の間、アルボラ以外の攻め手(ふだんはブロックを壊さない Mob)が壁を壊すのに、アルボラの何倍の時間がかかるか。
     * ユーザー指定 2026-09-25「TD 時のみ、すべての mob がブロックを破壊できるように。アルボラ以外の通常時はブロック破壊をしない
     * mob はアルボラよりもブロック破壊に 5 倍の時間がかかる」: 1 回の削り = アルボラの攻撃力(mobs.json の基礎値)÷ これ。
     * {@code TdSiege#wallDamage}。
     */
    public double otherBreakTimeMultiplier() {
        return otherBreakTimeMultiplier;
    }

    /**
     * 侵攻の間、汚染されたブロック(タグ {@code #corrupted} と苔で覆われたマス)を壊すのに要る量を何倍にするか = 壊すのにかかる時間の倍率。
     * ユーザー指定 2026-09-26「TD 時のみ汚染されたブロックは mob が破壊しやすくなるように」、0.25(4 倍壊しやすい)は仮決め。
     * 0〜1(0 = 1 回で壊れる)。{@code TdSiege#required}。
     */
    public double corruptedBreakTimeMultiplier() {
        return corruptedBreakTimeMultiplier;
    }

    /**
     * 崖・溝に架ける橋の幅(マス、3〜5)。ユーザー指定 2026-09-25「しっかりと G5 mob なども通れるよう幅 3 マスほどの橋」:
     * スクティムネス(幅 2.97 = 経路では 3 マス)に加えてファルキムネス(幅 3.06 = 経路では 4 マス)も通れる 4(仮決め)。
     * {@code TdBridges}。
     */
    public int bridgeWidth() {
        return bridgeWidth;
    }

    /**
     * 地上の仮想の群れの速さ(ブロック / tick)= いちばん遅い種の {@code mobs.json} の movementSpeed × これ。
     * 仮決め 2026-09-25: 1.0(アルボラ 0.36・スポロフィル 0.18 ブロック / tick)。
     */
    public double groundSpeedFactor() {
        return groundSpeedFactor;
    }

    /** 空の仮想の群れの速さ(ブロック / tick)。仮決め 2026-09-25: 0.5。 */
    public double airSpeed() {
        return airSpeed;
    }

    /** 予算が尽きたあと、ターゲットから実体化の距離より遠くにこれだけいた攻め手は消す(tick)。仮決め 2026-09-25: 1200。 */
    public int strayDiscardTicks() {
        return strayDiscardTicks;
    }

    /** 仮想の群れの胞子の雲と音を、この距離のプレイヤーに見せる(ブロック)。仮決め 2026-09-25: 160。 */
    public double presentationRadius() {
        return presentationRadius;
    }

    /** ボスバー「森の侵攻 — 残り N 体」を、ターゲットからこの距離のプレイヤーに出す(ブロック)。仮決め 2026-09-25: 192。 */
    public double bossBarRadius() {
        return bossBarRadius;
    }

    /** 侵攻中、ターゲットからこの距離にいるプレイヤーのまわりでは生態系防衛の兵を出さない(ブロック)。仮決め 2026-09-25: 256。 */
    public double defenseSuppressRadius() {
        return defenseSuppressRadius;
    }

    // --- ターゲットと壁 ---

    /** ターゲットブロックの最大耐久。仮決め 2026-09-25: 1000。 */
    public float targetMaxHp() {
        return targetMaxHp;
    }

    /** 壁を壊すのに要る削りの量 = max(1, 硬さ) × これ。仮決め 2026-09-25: 10。 */
    public float breakHardnessFactor() {
        return breakHardnessFactor;
    }

    /** 支援(スポロフィル)の 1 回の削り。仮決め 2026-09-25: 2。 */
    public float siegeDamageSupport() {
        return siegeDamageSupport;
    }

    /** 斥候(フィロセンス)の 1 回の削り。仮決め 2026-09-25: 0(ビームでプレイヤーや砲台を狙うので塊は削らない)。 */
    public float siegeDamageRangedScout() {
        return siegeDamageRangedScout;
    }

    // --- 負け ---

    /** 負けたときに拠点のまわりへ噴き出す汚染の半径(ブロック)。仮決め 2026-09-25: 24。0 で噴き出さない。 */
    public int defeatCorruptionRadius() {
        return defeatCorruptionRadius;
    }

    /** その汚染の境目の揺らぎ(ブロック、±)。仮決め 2026-09-25: 2。 */
    public double defeatCorruptionNoise() {
        return defeatCorruptionNoise;
    }

    // --- 攻め手の顔ぶれ(2026-09-25) ---

    /**
     * 地上の仮想の群れを<b>登る群れ</b>(swarm.json の templates.climb、アルボラだけ)にする割合。登る群れは攻城でも壁・天井を登って
     * ターゲットへ向かい、壁の上の防衛側を襲う。残りは今までどおり壁を壊して入る(ユーザー指定 2026-09-25「壁を壊して侵入する
     * グループや、壁を登って壁の上にいる player を攻撃する」。割合 0.35 は仮決め)。
     */
    public double climberShare() {
        return climberShare;
    }

    /**
     * 侵攻(予告を含む)の間、G4 以降でない敵対 Mob(G1〜G3 とバニラの敵対 Mob)を消し、新しく湧かせない範囲
     * (ユーザー指定 2026-09-25「TD 開始時には G4 以降の mob 以外の敵対 mob を消滅させて、TD 期間中は新規にスポーンしないように」)。
     * <b>0 = イベントのディメンション全体</b>(既定。ユーザー指定 2026-09-25「できうる限り処理を軽くするためにワールド全体にする」。
     * はじめは 256 の仮決めだった)、正の数 = ターゲットから水平にその距離の中、負の数 = 消さない・止めない。{@code TdHostiles}。
     */
    public double hostileClearRadius() {
        return hostileClearRadius;
    }

    /**
     * 地上の仮想の群れ 1 つに、グループ {@code group}({@value #ESCORT_MIN_GROUP}〜{@value #ESCORT_MAX_GROUP})の護衛を 1 体付ける確率。
     * 候補は侵攻が起きたフェーズに過繁茂バイオームで湧く種だけ(ユーザー指定 2026-09-25「TD で G4〜9 も。TD が起きたフェーズで湧く mob
     * のみ」)。確率は仮決め(G5 0.25・G6 0.15・G7 0.1・G8 0.1・G9 0.05)。範囲の外は 0。
     */
    public double escortChance(int group) {
        return group >= ESCORT_MIN_GROUP && group <= ESCORT_MAX_GROUP ? escortChance[group] : 0.0D;
    }

    /** 護衛に出さない種(td.json の escortExcluded)。 */
    public boolean escortExcluded(ResourceLocation id) {
        return escortExcluded.contains(id);
    }

    /**
     * 全部の値を決まった順に並べた文字列。同梱の json と Java の既定値を比べるテスト用
     * ({@code EcosystemTable#describe} と同じ使い方)。
     */
    public String describe() {
        Map<String, Object> out = new TreeMap<>();
        out.put("trigger", List.of(startBiomass, overgrownSearchRadius, warningTicks, cooldownTicks));
        out.put("players", List.of(pauseWithoutPlayersRadius, abandonTicks, bossBarRadius, defenseSuppressRadius,
                presentationRadius));
        out.put("swarms", List.of(concurrentCap, maxVirtualSwarms, swarmSpawnIntervalTicks, virtualReserve, virtualSpawnMin,
                virtualSpawnMax, virtualSpawnBeyondSight, virtualSpawnFarthest, materializeRadius, playerMaterializeRadius,
                airSwarmShare, groundSpeedFactor, airSpeed, strayDiscardTicks, maxAirAttackers));
        out.put("target", List.of(targetMaxHp, breakHardnessFactor, siegeDamageSupport, siegeDamageRangedScout,
                otherBreakTimeMultiplier, corruptedBreakTimeMultiplier));
        out.put("defeat", List.of(defeatCorruptionRadius, defeatCorruptionNoise));
        out.put("roster", List.of(climberShare, hostileClearRadius, Arrays.toString(escortChance), escortExcluded.toString()));
        out.put("bridge", List.of(bridgeWidth));
        out.put("direction", List.of(directionalShare, directionalSpread, directionalOvergrownBias));
        return out.toString();
    }

    /** {@code data/DesignData} と、値を差し替えた表を作るテストから。 */
    public static TdTable fromJson(@Nullable Json j) {
        return new TdTable(j);
    }

    public static final TdTable DEFAULT = new TdTable(null);
}
