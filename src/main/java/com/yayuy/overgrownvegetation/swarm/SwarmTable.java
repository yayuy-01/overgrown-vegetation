package com.yayuy.overgrownvegetation.swarm;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.phase.OvergrownSpawnProfile;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code swarm.json} — G4 の<b>群れ v2</b> の設計値(2026-09-25)。仕組みは {@link SwarmManager}。
 * 読み込みは {@code data/DesignData}(同梱の json・{@code run/config} の写し・この Java の既定値の 3 か所を揃える)。
 *
 * <p><b>ここの数値はすべて仮決め 2026-09-25</b>(ユーザーが決めたのは「1 群れ 1 司令塔・1 匹が先導」
 * 「編成は湧いた時点で揃った混成」「消えるときは群れごと」「暇なら止まって全員で汚染を広げる」
 * 「ヴェロフィルは空の群れを別に作る」という仕組みのほうで、数値は決めていない)。
 * 由来のあるもの: 獲物を探す半径 48 は旧 {@code InvaderPreyIntel}、待つ上限 300 tick は旧 {@code InvaderAdvanceGoal}、
 * 外へ向かう走査 192 / 余白 32 は旧 {@code invaderPackMarchRange / Margin}、残党の合流 32 は旧 {@code InvaderPackBonds}。
 *
 * <p>{@code /ov design reload} で即反映(実行中の群れも次の判断から新しい値を使う)。
 */
public final class SwarmTable {

    // --- json の形 ---

    public static final class Json {
        public MaxSizeJson maxSize;
        public Integer thinkIntervalTicks;
        public Double engageRadius;
        public Integer settleWindowTicks;
        public Integer trailCapacity;
        public FormationJson formation;
        public SpreadJson spread;
        public TargetsJson targets;
        public LegsJson legs;
        public PathsJson paths;
        public EnvelopeJson envelope;
        public RemnantJson remnant;
        public AirJson air;
        public TemplatesJson templates;
    }

    public static final class MaxSizeJson {
        public Integer ground;
        public Integer air;
    }

    public static final class FormationJson {
        public Double spacing;
        public Integer lanes;
        public Double laneOffset;
        public Double laggardDistance;
        public Integer waitCapTicks;
        public Double catchUpDistance;
        public Double catchUpSpeed;
    }

    public static final class SpreadJson {
        public Integer cadenceMinTicks;
        public Integer cadenceMaxTicks;
        public Integer capTicks;
        public Integer startGraceTicks;
    }

    public static final class TargetsJson {
        public Integer searchIntervalTicks;
        public Integer otherSearchIntervalTicks;
        public Double preySearchRadius;
        public Double leashMultiplier;
        public Integer giveUpTicks;
        public Integer localRetaliationTicks;
        public Integer retaliationMemoryTicks;
        public Double clearMargin;
        public Double groundDyLimit;
        public Double otherSearchHeight;
        public Double airHandRadius;
        public Double siegeEngageRadius;
        public Integer huntLostGraceTicks;
    }

    public static final class LegsJson {
        public Double maxLength;
        public List<Integer> backoffTicks;
        public Integer failedLegsBeforeReheading;
        public Double roamMin;
        public Double roamMax;
        public Double marchRange;
        public Double marchMargin;
        public Double destinationReachedDistance;
    }

    public static final class PathsJson {
        public Integer tokensPerTick;
        public Integer recoveryNoProgressTicks;
        public Integer recoveryMaxFailures;
    }

    public static final class EnvelopeJson {
        public Double maxHeight;
    }

    public static final class RemnantJson {
        public Integer minPeak;
        public Integer maxActive;
        public Double mergeRadius;
    }

    public static final class AirJson {
        public Double lookahead;
        public Double lateralSpacing;
        public Double alongTrackGain;
    }

    public static final class TemplatesJson {
        public List<TemplateJson> ground;
        public List<TemplateJson> air;
        /** 壁・天井を登る群れ(TD の攻め手の一部。群れの全員が登れる種 = アルボラだけにする。2026-09-25)。 */
        public List<TemplateJson> climb;
    }

    public static final class TemplateJson {
        public Integer minPhase;
        public List<MemberJson> members;
    }

    public static final class MemberJson {
        public String id;
        public Integer min;
        public Integer max;
        /** 1 体ごとの抽選確率(min を超える分)。省略なら {@code OvergrownSpawnProfile.spawnChance}(レア枠)。 */
        public Double chance;
    }

    // --- 編成の型 ---

    /** 編成の 1 行(種・頭数の幅・抽選確率)。種は実行時に引く(読み込みはエンティティの登録より前)。 */
    public record MemberSpec(ResourceLocation id, int min, int max, @Nullable Double chance) {
    }

    /** フェーズ {@code minPhase} 以降で使う編成。 */
    public record Template(int minPhase, List<MemberSpec> members) {

        /**
         * 編成を振る。各行について min 体は必ず、max までの残りは 1 体ずつ抽選({@code chance}、
         * 省略なら種の {@code spawnChance})。
         *
         * @param mainCount 先頭の行(主力)の頭数の希望(生態系スポーンの不足数など)。{@code [min, max]} に収める。null なら振る
         * @param maxSize   合計の上限(群れの上限)
         */
        public List<EntityType<?>> roll(RandomSource random, @Nullable Integer mainCount, int maxSize) {
            return roll(random, mainCount, maxSize, 1.0D);
        }

        /**
         * {@link #roll(RandomSource, Integer, int)} の、主力の頭数に {@code mainScale} を掛ける版(最低 1 体)。
         * 自然湧きが森の奥で群れを小さくするのに使う(順 1 の着手順 10、ユーザー指定 2026-10-04 —— 縁寄りの重みを掛ける。
         * d 12 なら 0.15 倍でアルボラ 1 体前後)。{@code mainCount} が null のとき・1 以上のときは今までどおり。
         */
        public List<EntityType<?>> roll(RandomSource random, @Nullable Integer mainCount, int maxSize, double mainScale) {
            List<EntityType<?>> out = new ArrayList<>();
            for (int i = 0; i < this.members.size(); i++) {
                MemberSpec spec = this.members.get(i);
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(spec.id()).orElse(null);
                if (type == null) {
                    continue;
                }
                int count;
                if (i == 0 && mainCount != null) {
                    count = Math.max(spec.min(), Math.min(spec.max(), mainCount));
                    if (mainScale < 1.0D) {
                        count = Math.max(1, (int) Math.round(count * Math.max(0.0D, mainScale)));
                    }
                } else {
                    double chance = spec.chance() != null ? spec.chance() : OvergrownSpawnProfile.spawnChance(type);
                    count = spec.min();
                    for (int extra = spec.min(); extra < spec.max(); extra++) {
                        if (random.nextDouble() < chance) {
                            count++;
                        }
                    }
                }
                for (int n = 0; n < count && out.size() < maxSize; n++) {
                    out.add(type);
                }
            }
            return out;
        }
    }

    // --- 値 ---

    private final int maxSizeGround;
    private final int maxSizeAir;
    private final int thinkIntervalTicks;
    private final double engageRadius;
    private final int settleWindowTicks;
    private final int trailCapacity;

    private final double formationSpacing;
    private final int lanes;
    private final double laneOffset;
    private final double laggardDistance;
    private final int waitCapTicks;
    private final double catchUpDistance;
    private final double catchUpSpeed;

    private final int spreadCadenceMinTicks;
    private final int spreadCadenceMaxTicks;
    private final int spreadCapTicks;
    private final int spreadStartGraceTicks;

    private final int targetSearchIntervalTicks;
    private final int otherSearchIntervalTicks;
    private final double preySearchRadius;
    private final double leashMultiplier;
    private final int giveUpTicks;
    private final int localRetaliationTicks;
    private final int retaliationMemoryTicks;
    private final double clearMargin;
    private final double groundDyLimit;
    private final double otherSearchHeight;
    private final double airHandRadius;
    private final double siegeEngageRadius;
    private final int huntLostGraceTicks;

    private final double legMaxLength;
    private final int[] legBackoffTicks;
    private final int failedLegsBeforeReheading;
    private final double roamMin;
    private final double roamMax;
    private final double marchRange;
    private final double marchMargin;
    private final double destinationReachedDistance;

    private final int tokensPerTick;
    private final int recoveryNoProgressTicks;
    private final int recoveryMaxFailures;

    private final double envelopeMaxHeight;

    private final int remnantMinPeak;
    private final int remnantMaxActive;
    private final double remnantMergeRadius;

    private final double airLookahead;
    private final double airLateralSpacing;
    private final double airAlongTrackGain;

    private final List<Template> groundTemplates;
    private final List<Template> airTemplates;
    private final List<Template> climbTemplates;

    private SwarmTable(@Nullable Json j) {
        Json src = j != null ? j : new Json();
        MaxSizeJson size = src.maxSize != null ? src.maxSize : new MaxSizeJson();
        FormationJson f = src.formation != null ? src.formation : new FormationJson();
        SpreadJson s = src.spread != null ? src.spread : new SpreadJson();
        TargetsJson t = src.targets != null ? src.targets : new TargetsJson();
        LegsJson l = src.legs != null ? src.legs : new LegsJson();
        PathsJson p = src.paths != null ? src.paths : new PathsJson();
        EnvelopeJson e = src.envelope != null ? src.envelope : new EnvelopeJson();
        RemnantJson r = src.remnant != null ? src.remnant : new RemnantJson();
        AirJson a = src.air != null ? src.air : new AirJson();

        this.maxSizeGround = clampInt(size.ground, 12, 1, 64);
        this.maxSizeAir = clampInt(size.air, 6, 1, 32);
        this.thinkIntervalTicks = clampInt(src.thinkIntervalTicks, 10, 1, 100);
        this.engageRadius = clampDouble(src.engageRadius, 12.0D, 1.0D, 64.0D);
        this.settleWindowTicks = clampInt(src.settleWindowTicks, 100, 0, 2400);
        this.trailCapacity = clampInt(src.trailCapacity, 96, 16, 1024);

        this.formationSpacing = clampDouble(f.spacing, 2.5D, 0.5D, 16.0D);
        this.lanes = clampInt(f.lanes, 3, 1, 5);
        this.laneOffset = clampDouble(f.laneOffset, 2.0D, 0.0D, 8.0D);
        this.laggardDistance = clampDouble(f.laggardDistance, 6.0D, 1.0D, 64.0D);
        this.waitCapTicks = clampInt(f.waitCapTicks, 300, 0, 12000);
        this.catchUpDistance = clampDouble(f.catchUpDistance, 10.0D, 0.0D, 128.0D);
        this.catchUpSpeed = clampDouble(f.catchUpSpeed, 1.3D, 1.0D, 4.0D);

        this.spreadCadenceMinTicks = clampInt(s.cadenceMinTicks, 1200, 20, 144000);
        this.spreadCadenceMaxTicks = Math.max(this.spreadCadenceMinTicks, clampInt(s.cadenceMaxTicks, 2400, 20, 144000));
        this.spreadCapTicks = clampInt(s.capTicks, 600, 20, 12000);
        this.spreadStartGraceTicks = clampInt(s.startGraceTicks, 200, 0, 12000);

        this.targetSearchIntervalTicks = clampInt(t.searchIntervalTicks, 20, 1, 1200);
        this.otherSearchIntervalTicks = clampInt(t.otherSearchIntervalTicks, 40, 1, 1200);
        this.preySearchRadius = clampDouble(t.preySearchRadius, 48.0D, 0.0D, 256.0D);
        this.leashMultiplier = clampDouble(t.leashMultiplier, 1.5D, 1.0D, 8.0D);
        this.giveUpTicks = clampInt(t.giveUpTicks, 200, 0, 12000);
        this.localRetaliationTicks = clampInt(t.localRetaliationTicks, 100, 0, 12000);
        this.retaliationMemoryTicks = clampInt(t.retaliationMemoryTicks, 600, 0, 72000);
        this.clearMargin = clampDouble(t.clearMargin, 4.0D, 0.0D, 64.0D);
        this.groundDyLimit = clampDouble(t.groundDyLimit, 4.0D, 0.0D, 64.0D);
        this.otherSearchHeight = clampDouble(t.otherSearchHeight, 8.0D, 1.0D, 128.0D);
        this.airHandRadius = clampDouble(t.airHandRadius, 64.0D, 1.0D, 256.0D);
        this.siegeEngageRadius = clampDouble(t.siegeEngageRadius, 24.0D, 0.0D, 128.0D);
        this.huntLostGraceTicks = clampInt(t.huntLostGraceTicks, 1200, 20, 72000);

        this.legMaxLength = clampDouble(l.maxLength, 24.0D, 4.0D, 64.0D);
        this.legBackoffTicks = backoff(l.backoffTicks != null ? l.backoffTicks : List.of(40, 80, 160));
        this.failedLegsBeforeReheading = clampInt(l.failedLegsBeforeReheading, 3, 1, 64);
        this.roamMin = clampDouble(l.roamMin, 24.0D, 4.0D, 256.0D);
        this.roamMax = Math.max(this.roamMin, clampDouble(l.roamMax, 48.0D, 4.0D, 256.0D));
        this.marchRange = clampDouble(l.marchRange, 192.0D, 0.0D, 512.0D);
        this.marchMargin = clampDouble(l.marchMargin, 32.0D, 0.0D, 256.0D);
        this.destinationReachedDistance = clampDouble(l.destinationReachedDistance, 6.0D, 1.0D, 64.0D);

        this.tokensPerTick = clampInt(p.tokensPerTick, 4, 1, 64);
        this.recoveryNoProgressTicks = clampInt(p.recoveryNoProgressTicks, 40, 1, 1200);
        this.recoveryMaxFailures = clampInt(p.recoveryMaxFailures, 3, 1, 64);

        this.envelopeMaxHeight = clampDouble(e.maxHeight, 5.0D, 1.0D, 32.0D);

        this.remnantMinPeak = clampInt(r.minPeak, 3, 2, 64);
        this.remnantMaxActive = clampInt(r.maxActive, 2, 1, 64);
        this.remnantMergeRadius = clampDouble(r.mergeRadius, 32.0D, 0.0D, 256.0D);

        this.airLookahead = clampDouble(a.lookahead, 40.0D, 1.0D, 256.0D);
        this.airLateralSpacing = clampDouble(a.lateralSpacing, 6.0D, 0.0D, 64.0D);
        this.airAlongTrackGain = clampDouble(a.alongTrackGain, 0.05D, 0.0D, 10.0D);

        TemplatesJson templates = src.templates;
        this.groundTemplates = templates(templates != null && templates.ground != null ? templates.ground : null,
                // G4 の群れはフェーズ4 から(2026-10-10 ユーザー指定で G4 の湧きを 5 から 1 つ前へ。編成表も 1 つ前へ)。
                List.of(new Template(4, List.of(spec("arbora", 4, 6))),
                        new Template(5, List.of(spec("arbora", 4, 6), spec("overgrown_shielder", 0, 1),
                                spec("sporophyll", 0, 1), spec("phyllosens", 0, 1)))));
        this.airTemplates = templates(templates != null && templates.air != null ? templates.air : null,
                List.of(new Template(4, List.of(spec("velophyll", 3, 5)))));
        this.climbTemplates = templates(templates != null && templates.climb != null ? templates.climb : null,
                List.of(new Template(4, List.of(spec("arbora", 4, 6)))));
    }

    // --- 読み込みの道具 ---

    private static int clampInt(@Nullable Integer v, int def, int min, int max) {
        return v == null ? def : Math.max(min, Math.min(max, v));
    }

    private static double clampDouble(@Nullable Double v, double def, double min, double max) {
        return v == null || !Double.isFinite(v) ? def : Math.max(min, Math.min(max, v));
    }

    private static int[] backoff(List<Integer> in) {
        List<Integer> out = new ArrayList<>();
        for (Integer v : in) {
            if (v != null && v >= 0) {
                out.add(Math.min(v, 12000));
            }
        }
        if (out.isEmpty()) {
            out.add(40);
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static MemberSpec spec(String path, int min, int max) {
        return new MemberSpec(OvergrownVegetation.id(path), min, max, null);
    }

    private static List<Template> templates(@Nullable List<TemplateJson> json, List<Template> def) {
        if (json == null) {
            return def;
        }
        List<Template> out = new ArrayList<>();
        for (TemplateJson t : json) {
            if (t == null || t.members == null) {
                continue;
            }
            List<MemberSpec> members = new ArrayList<>();
            for (MemberJson m : t.members) {
                if (m == null || m.id == null) {
                    continue;
                }
                ResourceLocation id = ResourceLocation.tryParse(m.id);
                if (id == null) {
                    OvergrownVegetation.LOGGER.warn("swarm.json: 種の id として読めません: {}", m.id);
                    continue;
                }
                if (!m.id.contains(":")) {
                    id = OvergrownVegetation.id(id.getPath());
                }
                int min = m.min != null ? Math.max(0, m.min) : 0;
                int max = Math.max(min, m.max != null ? m.max : min);
                Double chance = m.chance != null && Double.isFinite(m.chance) ? Math.max(0.0D, Math.min(1.0D, m.chance)) : null;
                members.add(new MemberSpec(id, min, max, chance));
            }
            if (!members.isEmpty()) {
                out.add(new Template(t.minPhase != null ? t.minPhase : 1, List.copyOf(members)));
            }
        }
        out.sort((x, y) -> Integer.compare(x.minPhase(), y.minPhase()));
        return List.copyOf(out);
    }

    // --- 値 ---

    /** 群れの頭数の上限(地上 / 空)。これを超えて入ることは無い。 */
    public int maxSize(Swarm.Kind kind) {
        return kind == Swarm.Kind.AIR ? maxSizeAir : maxSizeGround;
    }

    /** 司令塔が考える間隔(tick)。群れごとに ID で位相をずらす。 */
    public int thinkIntervalTicks() {
        return thinkIntervalTicks;
    }

    /** 前衛へ標的を渡す距離(ブロック)。群れで囲んで殴る間合いでもある。 */
    public double engageRadius() {
        return engageRadius;
    }

    /** 組み直した直後、より先導に向いた個体が入ってきたら選び直す時間(tick)。残党・空きの判定もこの間はしない。 */
    public int settleWindowTicks() {
        return settleWindowTicks;
    }

    /** 足跡(先導役の通り道の中心)を覚えておく点の数。 */
    public int trailCapacity() {
        return trailCapacity;
    }

    /** 陣形の列の間隔(ブロック)。 */
    public double formationSpacing() {
        return formationSpacing;
    }

    /** 陣形の横の並び数(1 列目 = 中央、2 = 右、3 = 左)。 */
    public int lanes() {
        return lanes;
    }

    /** 横の並びの間隔(ブロック)。 */
    public double laneOffset() {
        return laneOffset;
    }

    /** 自分の陣形の位置からこれ以上離れたら「遅れている」(ブロック)。 */
    public double laggardDistance() {
        return laggardDistance;
    }

    /** 遅れている仲間を先導役が待つ上限(tick)。旧 {@code InvaderAdvanceGoal} と同じ 300。 */
    public int waitCapTicks() {
        return waitCapTicks;
    }

    /** 陣形の位置からこれ以上離れた後続は走って追いつく(ブロック)。 */
    public double catchUpDistance() {
        return catchUpDistance;
    }

    /** 追いつくときの速さの倍率。 */
    public double catchUpSpeed() {
        return catchUpSpeed;
    }

    /** 暇になってから汚染を広げるまでの間隔の下限・上限(tick、暇な間だけ数える)。 */
    public int spreadCadenceMinTicks() {
        return spreadCadenceMinTicks;
    }

    public int spreadCadenceMaxTicks() {
        return spreadCadenceMaxTicks;
    }

    /** 1 回の汚染を広げる時間の上限(tick)。 */
    public int spreadCapTicks() {
        return spreadCapTicks;
    }

    /** 汚染を広げ始めてから、誰も始めなければ打ち切るまで(tick)。 */
    public int spreadStartGraceTicks() {
        return spreadStartGraceTicks;
    }

    /** 標的を探し直す間隔(tick)。 */
    public int targetSearchIntervalTicks() {
        return targetSearchIntervalTicks;
    }

    /** プレイヤー以外(獲物・部外者)を箱で探す間隔(tick)。 */
    public int otherSearchIntervalTicks() {
        return otherSearchIntervalTicks;
    }

    /** グループ1〜3 の獲物を探す半径(ブロック)。旧 {@code InvaderPreyIntel} と同じ 48。 */
    public double preySearchRadius() {
        return preySearchRadius;
    }

    /** 追いかけ続ける距離 = 探す距離 × これ。 */
    public double leashMultiplier() {
        return leashMultiplier;
    }

    /** 諦めた相手を群れとして狙わずにおく時間(tick)。 */
    public int giveUpTicks() {
        return giveUpTicks;
    }

    /** 殴られた個体が自分だけで反撃する時間(tick)。群れの標的は変えない。 */
    public int localRetaliationTicks() {
        return localRetaliationTicks;
    }

    /** プレイヤーへの反撃を最優先にしておく時間(tick)。過ぎたら普通のプレイヤーの扱いに戻る。 */
    public int retaliationMemoryTicks() {
        return retaliationMemoryTicks;
    }

    /** 渡した標的を取り上げるのは、役割の距離 + これを超えてから(ブロック、揺れよけ)。 */
    public double clearMargin() {
        return clearMargin;
    }

    /** 壁を登れない前衛へ標的を渡す高さの差の上限(ブロック)。 */
    public double groundDyLimit() {
        return groundDyLimit;
    }

    /** 獲物・部外者を探す箱の上下(ブロック、地上の群れ)。 */
    public double otherSearchHeight() {
        return otherSearchHeight;
    }

    /** 空の群れの全員へ標的を渡す水平距離(ブロック)。ヴェロフィルの索敵距離と同じ 64。 */
    public double airHandRadius() {
        return airHandRadius;
    }

    /** 攻城(TD)中、標的の塊より先に相手をする防衛側の距離(先導役から、ブロック)。 */
    public double siegeEngageRadius() {
        return siegeEngageRadius;
    }

    /** 小規模レイドの狙いのプレイヤーが見つからないまま待つ時間(tick)。旧 {@code SmallRaidHuntGoal} と同じ。 */
    public int huntLostGraceTicks() {
        return huntLostGraceTicks;
    }

    /** 先導役が 1 回に引く区間の長さの上限(ブロック)。 */
    public double legMaxLength() {
        return legMaxLength;
    }

    /** 区間が引けない・進まないとき、次に引くまで待つ時間(tick)。失敗が続くほど後ろの値。 */
    public int legBackoffTicks(int failures) {
        return legBackoffTicks[Math.max(0, Math.min(failures, legBackoffTicks.length - 1))];
    }

    /** 同じ行き先へ区間がこの回数続けて失敗したら、次の向きへ切り替える。 */
    public int failedLegsBeforeReheading() {
        return failedLegsBeforeReheading;
    }

    /** 外に出たあと・向きを使い切ったあとに選ぶ、ぶらつく先の距離(ブロック)。 */
    public double roamMin() {
        return roamMin;
    }

    public double roamMax() {
        return roamMax;
    }

    /** 過繁茂バイオームの外を探す距離(ブロック)。旧 {@code invaderPackMarchRange}。 */
    public double marchRange() {
        return marchRange;
    }

    /** 見つけた境目の先に置く余白(ブロック)。旧 {@code invaderPackMarchMargin}。 */
    public double marchMargin() {
        return marchMargin;
    }

    /** 行き先に「着いた」とみなす距離(ブロック、水平)。 */
    public double destinationReachedDistance() {
        return destinationReachedDistance;
    }

    /** 群れが 1 tick に使ってよい経路探索の数(ワールドごと)。 */
    public int tokensPerTick() {
        return tokensPerTick;
    }

    /** 後続が進めないまま(先導役は動いている)これだけ経ったら、自分で経路を引いて立て直す(tick)。 */
    public int recoveryNoProgressTicks() {
        return recoveryNoProgressTicks;
    }

    /** 立て直しがこの回数続けて失敗したら、自然の群れからは外す(イベントの群れは外さない)。 */
    public int recoveryMaxFailures() {
        return recoveryMaxFailures;
    }

    /** 体の包絡の高さの上限(ブロック)。スポロフィルの背丈(約 5)。 */
    public double envelopeMaxHeight() {
        return envelopeMaxHeight;
    }

    /** これ以上の頭数だったことがある群れだけが残党になりうる。 */
    public int remnantMinPeak() {
        return remnantMinPeak;
    }

    /** 動いている頭数がこれ以下になったら残党。 */
    public int remnantMaxActive() {
        return remnantMaxActive;
    }

    /** 残党が合流する相手を探す距離(ブロック)。 */
    public double remnantMergeRadius() {
        return remnantMergeRadius;
    }

    /** 空の後続が狙う点の、先導役の前方への距離(ブロック、旋回半径より長く)。 */
    public double airLookahead() {
        return airLookahead;
    }

    /** 空の V 字の横の間隔(ブロック)。 */
    public double airLateralSpacing() {
        return airLateralSpacing;
    }

    /** 空の後続の速さ = 先導役の速さ + これ × 前後の遅れ(ブロック)。 */
    public double airAlongTrackGain() {
        return airAlongTrackGain;
    }

    /** フェーズ {@code phase} で使う編成(minPhase がいちばん大きく phase 以下のもの)。無ければ null。 */
    @Nullable
    public Template template(int phase, Swarm.Kind kind) {
        List<Template> list = kind == Swarm.Kind.AIR ? airTemplates : groundTemplates;
        return pick(list, phase);
    }

    /**
     * フェーズ {@code phase} の<b>登る群れ</b>の編成(TD の攻め手の一部。2026-09-25 のユーザー指定「アルボラは壁や天井を通って
     * ターゲットブロックへ行くルートも」)。登れない種を入れると群れは登らない(包絡)ので、ふつうはアルボラだけ。無ければ null。
     */
    @Nullable
    public Template climbTemplate(int phase) {
        return pick(climbTemplates, phase);
    }

    @Nullable
    private static Template pick(List<Template> list, int phase) {
        Template best = null;
        for (Template t : list) {
            if (t.minPhase() <= phase) {
                best = t;
            }
        }
        return best;
    }

    /**
     * 全部の値を決まった順に並べた文字列。同梱の json と Java の既定値を比べるテスト用
     * ({@code EcosystemTable#describe} と同じ使い方)。
     */
    public String describe() {
        Map<String, Object> out = new TreeMap<>();
        out.put("maxSize", maxSizeGround + "/" + maxSizeAir);
        out.put("thinkIntervalTicks", thinkIntervalTicks);
        out.put("engageRadius", engageRadius);
        out.put("settleWindowTicks", settleWindowTicks);
        out.put("trailCapacity", trailCapacity);
        out.put("formation", List.of(formationSpacing, lanes, laneOffset, laggardDistance, waitCapTicks,
                catchUpDistance, catchUpSpeed));
        out.put("spread", List.of(spreadCadenceMinTicks, spreadCadenceMaxTicks, spreadCapTicks, spreadStartGraceTicks));
        out.put("targets", List.of(targetSearchIntervalTicks, otherSearchIntervalTicks, preySearchRadius, leashMultiplier,
                giveUpTicks, localRetaliationTicks, retaliationMemoryTicks, clearMargin, groundDyLimit,
                otherSearchHeight, airHandRadius, siegeEngageRadius, huntLostGraceTicks));
        out.put("legs", List.of(legMaxLength, Arrays.toString(legBackoffTicks), failedLegsBeforeReheading, roamMin, roamMax,
                marchRange, marchMargin, destinationReachedDistance));
        out.put("paths", List.of(tokensPerTick, recoveryNoProgressTicks, recoveryMaxFailures));
        out.put("envelopeMaxHeight", envelopeMaxHeight);
        out.put("remnant", List.of(remnantMinPeak, remnantMaxActive, remnantMergeRadius));
        out.put("air", List.of(airLookahead, airLateralSpacing, airAlongTrackGain));
        out.put("groundTemplates", groundTemplates.toString());
        out.put("airTemplates", airTemplates.toString());
        out.put("climbTemplates", climbTemplates.toString());
        return out.toString();
    }

    /** {@code data/DesignData} と、値を差し替えた表を作るテストから。 */
    public static SwarmTable fromJson(@Nullable Json j) {
        return new SwarmTable(j);
    }

    public static final SwarmTable DEFAULT = new SwarmTable(null);
}
