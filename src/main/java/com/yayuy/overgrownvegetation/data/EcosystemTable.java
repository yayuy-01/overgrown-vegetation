package com.yayuy.overgrownvegetation.data;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code ecosystem.json} — <b>生態系の台帳(兵站)</b>の設計値(2026-09-24)。仕組みは {@code ecosystem/Ecosystem}。
 *
 * <p>ユーザー指定の値:
 * <ul>
 *     <li>{@code regionChunks} — 区域の一辺(チャンク)。既定 <b>8</b>。2026-10-14 から多様性はワールドで 1 つになり、
 *     区域は「防衛の兵を出すとき、プレイヤーの近く(この区域)に過繁茂があるか」の目安にだけ使う。</li>
 *     <li>{@code recoveryDays} — 多様性(豊かさ)が 0% から 100% まで戻る日数(ゲーム内)。既定 <b>5</b>。</li>
 *     <li>{@code countedDamageTypes} — プレイヤー以外で豊かさを減らす一撃(防衛設備)。既定は機関砲台と有刺鉄線。
 *     プレイヤー(と、プレイヤーが撃った矢などの間接攻撃)は常に数える。</li>
 *     <li>{@code biomassPerSecond} / {@code biomassPerStep} / {@code biomassChunksPerStep} — バイオマスは毎秒 <b>1</b>、
 *     過繁茂のチャンク <b>100</b> ごとに <b>+1</b> ずつ増え、ワールドの多様性を掛ける(2026-10-14 ユーザー指定)。</li>
 *     <li>{@code defense.thresholds} — 防衛の段階のしきい値。既定 <b>70% / 40% / 15%</b>(下回るとグループ4 / 5 / 6 まで出る)。</li>
 *     <li>{@code defense.cost} — 防衛の兵 1 体に使うバイオマス。既定 <b>グループ4 = 100 / 5 = 1500 / 6 = 4500</b>。</li>
 * </ul>
 * <p>仮決めの値: {@code healthLoss}(グループ2 = 2% / グループ3 = 5%)、{@code defense} の
 * {@code hysteresis}・{@code intervalTicks}・距離・{@code defenders}(段階ごとの頭数)。
 *
 * <p>グループは {@code "group_4"} のように書く(タグ名と同じ)。
 */
public final class EcosystemTable {

    public static final class Json {
        public Integer regionChunks;
        public Double recoveryDays;
        /** グループ → 1 体倒されたときに減る豊かさ(0〜1)。 */
        public Map<String, Double> healthLoss;
        /** 種の id → 減る豊かさ。書いた種は {@code healthLoss} より優先する。 */
        public Map<String, Double> speciesLoss;
        public List<String> countedDamageTypes;
        public Double biomassPerSecond;
        public Double biomassPerStep;
        public Integer biomassChunksPerStep;
        /**
         * バイオマスの貯蓄上限(2026-10-23 追記 14【決定】: 基本 + 巨大樹 1 本ごと、ハイヴノードを倒すとその 1 本分が減る。
         * 基本は TD の開始量 td.json startBiomass より大きく。数は仮決め)。収入はここで止まる(侵攻の払い戻しとコマンドは上限を見ない)。
         */
        public Double biomassCapBase, biomassCapPerTree;
        public Integer incomeIntervalTicks;
        public DefenseJson defense;
    }

    public static final class DefenseJson {
        /** 段階 1〜3 のしきい値(豊かさがこれを下回ると、その段階に入る)。大きい順。 */
        public List<Double> thresholds;
        /** 段階を下げる(防衛をやめる)のは、しきい値 + これ まで戻ってから。 */
        public Double hysteresis;
        public Integer intervalTicks;
        public Integer minDistance;
        public Integer maxDistance;
        /** 「今いる防衛の兵」を数える、プレイヤーからの半径。 */
        public Integer countRadius;
        /** グループ → 段階 1〜3 のときにプレイヤーの周りへ揃える頭数。 */
        public Map<String, List<Integer>> defenders;
        /** グループ → 1 体に使うバイオマス。 */
        public Map<String, Double> cost;
    }

    /** 防衛の兵に使えるグループ(番号が上がるほど強い)。 */
    public static final int FIRST_DEFENDER_GROUP = 4;
    public static final int LAST_DEFENDER_GROUP = 6;

    private final int regionChunks;
    private final double recoveryDays;
    private final Int2DoubleOpenHashMap groupLoss = new Int2DoubleOpenHashMap();
    private final Map<String, Double> speciesLoss = new HashMap<>();
    private final List<ResourceKey<DamageType>> countedDamageTypes;
    private final double biomassPerSecond;
    private final double biomassPerStep;
    private final double biomassCapBase, biomassCapPerTree;
    private final int biomassChunksPerStep;
    private final int incomeIntervalTicks;
    private final double[] thresholds;
    private final double hysteresis;
    private final int defenseIntervalTicks;
    private final int minDistance;
    private final int maxDistance;
    private final int countRadius;
    private final Int2ObjectOpenHashMap<int[]> defenders = new Int2ObjectOpenHashMap<>();
    private final Int2DoubleOpenHashMap cost = new Int2DoubleOpenHashMap();

    private EcosystemTable(Json j) {
        Json src = j != null ? j : new Json();
        DefenseJson d = src.defense != null ? src.defense : new DefenseJson();

        this.regionChunks = src.regionChunks != null ? Math.max(1, src.regionChunks) : 8;
        this.recoveryDays = src.recoveryDays != null && src.recoveryDays > 0.0D ? src.recoveryDays : 5.0D;
        putGroups(groupLoss, src.healthLoss != null ? src.healthLoss : Map.of("group_2", 0.02D, "group_3", 0.05D));
        if (src.speciesLoss != null) {
            for (Map.Entry<String, Double> e : src.speciesLoss.entrySet()) {
                ResourceLocation id = speciesId(e.getKey());
                if (id != null && e.getValue() != null) {
                    speciesLoss.put(id.toString(), clamp01(e.getValue()));
                }
            }
        }
        this.countedDamageTypes = damageTypeKeys(src.countedDamageTypes != null ? src.countedDamageTypes
                : List.of("overgrown_vegetation:autocannon", "overgrown_vegetation:barbed_wire"));
        this.biomassPerSecond = src.biomassPerSecond != null ? Math.max(0.0D, src.biomassPerSecond) : 1.0D;
        this.biomassPerStep = src.biomassPerStep != null ? Math.max(0.0D, src.biomassPerStep) : 1.0D;
        this.biomassChunksPerStep = src.biomassChunksPerStep != null ? Math.max(1, src.biomassChunksPerStep) : 100;
        this.biomassCapBase = src.biomassCapBase != null ? Math.max(0.0D, src.biomassCapBase) : 10000.0D;
        this.biomassCapPerTree = src.biomassCapPerTree != null ? Math.max(0.0D, src.biomassCapPerTree) : 2000.0D;
        this.incomeIntervalTicks = src.incomeIntervalTicks != null ? Math.max(20, src.incomeIntervalTicks) : 20;

        this.thresholds = thresholds(d.thresholds != null ? d.thresholds : List.of(0.70D, 0.40D, 0.15D));
        this.hysteresis = d.hysteresis != null ? Math.max(0.0D, d.hysteresis) : 0.05D;
        this.defenseIntervalTicks = d.intervalTicks != null ? Math.max(20, d.intervalTicks) : 200;
        this.minDistance = d.minDistance != null ? Math.max(1, d.minDistance) : 24;
        this.maxDistance = Math.max(this.minDistance, d.maxDistance != null ? d.maxDistance : 48);
        this.countRadius = d.countRadius != null ? Math.max(1, d.countRadius) : 64;
        Map<String, List<Integer>> defenderJson = d.defenders != null ? d.defenders : Map.of(
                "group_4", List.of(3, 4, 5),
                "group_5", List.of(0, 1, 2),
                "group_6", List.of(0, 0, 1));
        for (Map.Entry<String, List<Integer>> e : defenderJson.entrySet()) {
            int group = groupNumber(e.getKey());
            if (group < 0 || e.getValue() == null) {
                continue;
            }
            int[] counts = new int[thresholds.length];
            for (int i = 0; i < counts.length && i < e.getValue().size(); i++) {
                Integer n = e.getValue().get(i);
                counts[i] = n != null ? Math.max(0, n) : 0;
            }
            defenders.put(group, counts);
        }
        putGroups(cost, d.cost != null ? d.cost : Map.of("group_4", 100.0D, "group_5", 1500.0D, "group_6", 4500.0D),
                false);
    }

    // --- 読み込みの道具 ---

    private static void putGroups(Int2DoubleOpenHashMap out, Map<String, Double> in) {
        putGroups(out, in, true);
    }

    private static void putGroups(Int2DoubleOpenHashMap out, Map<String, Double> in, boolean fraction) {
        for (Map.Entry<String, Double> e : in.entrySet()) {
            int group = groupNumber(e.getKey());
            if (group < 0 || e.getValue() == null) {
                continue;
            }
            out.put(group, fraction ? clamp01(e.getValue()) : Math.max(0.0D, e.getValue()));
        }
    }

    /** {@code "group_4"} → 4。読めなければ -1(警告を出す)。 */
    private static int groupNumber(String key) {
        if (key != null && key.startsWith("group_")) {
            try {
                int n = Integer.parseInt(key.substring("group_".length()));
                if (MobGroup.byNumber(n) != null) {
                    return n;
                }
            } catch (NumberFormatException ignored) {
                // 下で警告する
            }
        }
        OvergrownVegetation.LOGGER.warn("ecosystem.json: グループとして読めません(group_1〜group_10): {}", key);
        return -1;
    }

    /** しきい値は大きい順に並べ、0〜1 に収める(段階 1 がいちばん浅い)。 */
    private static double[] thresholds(List<Double> in) {
        List<Double> list = new ArrayList<>();
        for (Double v : in) {
            if (v != null) {
                list.add(clamp01(v));
            }
        }
        list.sort((a, b) -> Double.compare(b, a));
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    private static ResourceLocation speciesId(String raw) {
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null) {
            OvergrownVegetation.LOGGER.warn("ecosystem.json: 種の id として読めません: {}", raw);
            return null;
        }
        return raw.contains(":") ? id : OvergrownVegetation.id(id.getPath());
    }

    private static List<ResourceKey<DamageType>> damageTypeKeys(List<String> list) {
        List<ResourceKey<DamageType>> out = new ArrayList<>();
        for (String raw : list) {
            ResourceLocation id = raw != null ? ResourceLocation.tryParse(raw) : null;
            if (id == null) {
                OvergrownVegetation.LOGGER.warn("ecosystem.json: ダメージタイプの id として読めません: {}", raw);
                continue;
            }
            out.add(ResourceKey.create(Registries.DAMAGE_TYPE, id));
        }
        return List.copyOf(out);
    }

    private static double clamp01(double v) {
        return Math.max(0.0D, Math.min(1.0D, v));
    }

    // --- 値 ---

    /** 区域の一辺(チャンク)。 */
    public int regionChunks() {
        return regionChunks;
    }

    public double recoveryDays() {
        return recoveryDays;
    }

    /** 1 tick で戻る豊かさ(0〜1)。 */
    public double recoveryPerTick() {
        return 1.0D / (recoveryDays * 24000.0D);
    }

    /** {@code type}(グループ {@code group})が 1 体倒されたときに減る豊かさ。数えない種は 0。 */
    public double healthLoss(EntityType<?> type, MobGroup group) {
        Double bySpecies = speciesLoss.get(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
        if (bySpecies != null) {
            return bySpecies;
        }
        return group != null ? groupLoss.getOrDefault(group.number(), 0.0D) : 0.0D;
    }

    public List<ResourceKey<DamageType>> countedDamageTypes() {
        return countedDamageTypes;
    }

    /** 毎秒のバイオマスの基本(既定 1)。 */
    public double biomassPerSecond() {
        return biomassPerSecond;
    }

    /** バイオマスの貯蓄上限 = 基本 + 生きている巨大樹(ハイヴノードを倒されていない木)× 1 本分。 */
    public double biomassCap(int livingTrees) {
        return biomassCapBase + biomassCapPerTree * Math.max(0, livingTrees);
    }

    /** 過繁茂のチャンク {@link #biomassChunksPerStep} ごとに足す毎秒のバイオマス(既定 1)。 */
    public double biomassPerStep() {
        return biomassPerStep;
    }

    /** 何チャンクごとに {@link #biomassPerStep} を足すか(既定 100)。 */
    public int biomassChunksPerStep() {
        return biomassChunksPerStep;
    }

    public int incomeIntervalTicks() {
        return incomeIntervalTicks;
    }

    /** 段階 1〜の しきい値(大きい順)。呼ぶたびに複製を返す。 */
    public double[] thresholds() {
        return thresholds.clone();
    }

    /** 段階の数(= しきい値の数)。 */
    public int tiers() {
        return thresholds.length;
    }

    public double hysteresis() {
        return hysteresis;
    }

    public int defenseIntervalTicks() {
        return defenseIntervalTicks;
    }

    public int minDistance() {
        return minDistance;
    }

    public int maxDistance() {
        return maxDistance;
    }

    public int countRadius() {
        return countRadius;
    }

    /** 段階 {@code tier}(1〜)のとき、グループ {@code group} を何体揃えるか。 */
    public int defenders(int group, int tier) {
        int[] counts = defenders.get(group);
        if (counts == null || tier < 1 || tier > counts.length) {
            return 0;
        }
        return counts[tier - 1];
    }

    /** グループ {@code group} の防衛の兵 1 体に使うバイオマス。書かれていなければ {@code Double.MAX_VALUE}(出さない)。 */
    public double cost(int group) {
        return cost.getOrDefault(group, Double.MAX_VALUE);
    }

    /**
     * 全部の値を決まった順に並べた文字列。同梱の json と Java の既定値を比べるテスト用
     * (マップの順番や数値の型の違いで食い違いを見落とさないように)。
     */
    public String describe() {
        Map<String, Object> out = new TreeMap<>();
        out.put("regionChunks", regionChunks);
        out.put("recoveryDays", recoveryDays);
        out.put("groupLoss", new TreeMap<>(groupLoss));
        out.put("speciesLoss", new TreeMap<>(speciesLoss));
        out.put("countedDamageTypes", countedDamageTypes.stream().map(k -> k.location().toString()).toList());
        out.put("biomassPerSecond", biomassPerSecond);
        out.put("biomassPerStep", biomassPerStep);
        out.put("biomassChunksPerStep", biomassChunksPerStep);
        out.put("incomeIntervalTicks", incomeIntervalTicks);
        out.put("thresholds", Arrays.toString(thresholds));
        out.put("hysteresis", hysteresis);
        out.put("defenseIntervalTicks", defenseIntervalTicks);
        out.put("distance", minDistance + ".." + maxDistance);
        out.put("countRadius", countRadius);
        Map<Integer, String> defenderText = new TreeMap<>();
        defenders.forEach((group, counts) -> defenderText.put(group, Arrays.toString(counts)));
        out.put("defenders", defenderText);
        out.put("cost", new TreeMap<>(cost));
        return out.toString();
    }

    /** {@link DesignData} と、値を差し替えた表を作るテストから。 */
    public static EcosystemTable fromJson(Json j) {
        return new EcosystemTable(j);
    }

    public static final EcosystemTable DEFAULT = new EcosystemTable(null);
}
