package com.yayuy.overgrownvegetation.data;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code research.json} — 研究システムの「研究ツリー」設計データ。
 *
 * <p>Mob ステータス・装備と同じ思想で、Java ハードコードではなく
 * {@code config/overgrown_vegetation/research.json} から読む。既定値({@link #DEFAULT})は
 * {@link #buildDefault()} に写経してあり、json 欠損時のフォールバックになる。
 *
 * <p>装備({@link GearTable})と違って研究の数値は実行時に随時参照されるため、
 * {@code /ov design reload} で即反映できる(クライアントのツリー表示は
 * reload 後にサーバーから再同期される)。
 *
 * <p>ノードの {@code tier} は「タブ」ではなく**独立したツリー**を表す — Tier I と Tier II は
 * 別々の研究ツリーで、ゲーム内では同時に画面に出ない(タブ切替で表示を差し替える)。
 * 親(parents)は同じ tier のノードだけを指すこと(別 tier をまたぐ接続は描画されない)。
 *
 * <h2>形</h2>
 * <pre>
 * {
 *   "defaultPoints": 0,                       // itemPoints に無いアイテム1個あたりのポイント
 *   "itemPoints": { "overgrown_vegetation:phalangiotarbus_fang": 4, ... },
 *   "layout": { "nodeWidth": 120, "nodeHeight": 28, "spacingX": 172, "spacingY": 80 },  // 既定サイズ + 格子間隔
 *   // ノードごとに "widthCells" / "heightCells"(格子単位。1.0 = 座標点1つぶん)を書くと
 *   // そのノードだけ別の大きさになる
 *   "nodes": {
 *     "exoskeleton_armor": {
 *       "name": "スカトゥリウムの外骨格の防具", "description": "...",
 *       "icon": { "type": "item", "id": "overgrown_vegetation:exoskeleton_chestplate" },
 *       "cost": 5, "x": 0, "y": 0,
 *       "parents": [],
 *       "parentMode": "all",          // "all"(既定)= 親を全部開放しないと不可 / "any" = どれか1つでよい
 *       "exclusiveGroup": "",         // 同 tier で同じ名前 = 二者択一(1つ選ぶと他は永久に選べない)
 *       "unlocks": [ "exoskeleton_helmet", "exoskeleton_chestplate", ... ]  // レシピ id
 *     }
 *   }
 * }
 * </pre>
 */
public final class ResearchTable {

    // ───────────────────────────────────────────────────────────── DTO(Gson)

    public static final class Json {
        public Integer defaultPoints;
        public Map<String, Integer> itemPoints;
        public LayoutJson layout;
        public Map<String, NodeJson> nodes;
    }

    public static final class LayoutJson {
        /** Minecraft 側の研究ノード枠のピクセルサイズ(既定 120×28)。 */
        public Integer nodeWidth;
        public Integer nodeHeight;
        /** ノード同士の格子間隔(既定 172×80)。ノードサイズより詰めると重なるので注意。 */
        public Integer spacingX;
        public Integer spacingY;
    }

    public static final class NodeJson {
        public String name;
        public String description;
        public IconJson icon;
        public Integer cost;
        /** 研究ツリー画面の左タブ(Tier I〜VI)。1〜6、既定 1。 */
        public Integer tier;
        /** このノードを開放したときに増える解析枠の数(既定 0)。合計は基本 1 枠 + 全開放ノードの合計。 */
        public Integer analyzeSlots;
        public Double x;
        public Double y;
        /**
         * 任意: このノードだけの枠サイズ。単位は**格子(グリッド)** — {@code 1.0} で
         * {@code layout.spacingX} / {@code spacingY} ちょうど、つまり隣の座標点まで届く大きさになる。
         * 未指定なら {@code layout} の既定ピクセルサイズを使う。
         */
        public Double widthCells;
        public Double heightCells;
        public List<String> parents;
        /**
         * 親(parents)の満たし方。{@code "all"}(既定)= 並んでいる親を**すべて**開放していないと
         * このノードは開放できない。{@code "any"} = 親のうち**どれか1つ**が開放済みならよい。
         */
        public String parentMode;
        /**
         * 二者択一(排他)グループ名。同じ tier で同じ名前を持つノードは**1つしか開放できない**
         * (1つ開放すると同じグループの残りは永久に選べなくなる)。空/未指定 = 排他なし。
         */
        public String exclusiveGroup;
        public List<String> unlocks;
    }

    public static final class IconJson {
        /** {@code "item"} または {@code "texture"}。 */
        public String type;
        /** item なら アイテム id、texture なら {@code textures/research/<id>.png} の {@code <id>}。 */
        public String id;
    }

    // ─────────────────────────────────────────────────────────── 解決済みの値

    public record Icon(String type, String id) {
        public boolean isTexture() {
            return "texture".equals(type);
        }
    }

    /**
     * @param width          このノードの枠サイズ(ピクセル)。json で未指定なら {@link Layout} の既定値が入る。
     * @param anyParent      true = 親のどれか1つが開放済みならよい(OR)。false = 全部必要(AND、既定)。
     * @param exclusiveGroup 二者択一グループ名(空 = 排他なし)。同じ tier・同じ名前のノードは1つだけ開放できる。
     */
    public record Node(String id, String name, String description, Icon icon, int cost, int tier,
                       int analyzeSlots, double x, double y, List<String> parents, List<String> unlocks,
                       boolean anyParent, String exclusiveGroup, int width, int height) {
    }

    /**
     * Minecraft 側のツリー画面のノードサイズ・間隔(ピクセル)。研究タブごとに1つ、
     * mod マネージャーで編集して見た目の密度を調整できる。
     */
    public record Layout(int nodeWidth, int nodeHeight, int spacingX, int spacingY) {
        public static final Layout DEFAULT = new Layout(120, 28, 172, 80);
    }

    // ───────────────────────────────────────────────────────────────── 本体

    private final int defaultPoints;
    private final Map<String, Integer> itemPoints;
    private final Layout layout;
    private final Map<String, Node> nodes;
    private final Map<String, String> recipeToNode;

    private ResearchTable(int defaultPoints, Map<String, Integer> itemPoints, Layout layout, Map<String, Node> nodes) {
        this.defaultPoints = defaultPoints;
        this.itemPoints = itemPoints;
        this.layout = layout;
        this.nodes = nodes;
        Map<String, String> r = new LinkedHashMap<>();
        for (Node n : nodes.values()) {
            for (String recipe : n.unlocks()) {
                r.put(normalizeId(recipe), n.id());
            }
        }
        this.recipeToNode = r;
    }

    public int defaultPoints() {
        return defaultPoints;
    }

    public Layout layout() {
        return layout;
    }

    public Map<String, Node> nodes() {
        return nodes;
    }

    public Node node(String id) {
        return nodes.get(id);
    }

    /** レシピ id(名前空間付きに正規化した文字列)→ そのレシピを開放するノード id。無ければ null(ゲート対象外)。 */
    public String nodeForRecipe(ResourceLocation recipeId) {
        return recipeToNode.get(recipeId.toString());
    }

    /** {@code stack} 1個あたりの研究ポイント。 */
    public int pointsFor(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        int points = itemPoints.getOrDefault(id.toString(), defaultPoints);
        // しおれた地脈の花(2026-10-24、前兆)は半分(切り捨て)
        return com.yayuy.overgrownvegetation.block.LeyFlowerBlock.isWilted(stack) ? points / 2 : points;
    }

    /** 基本 1 枠 + 開放済みノードの {@code analyzeSlots} 合計(呼び出し側で上限クランプ推奨)。 */
    public int analyzeSlotsTotal(java.util.Set<String> unlocked) {
        int n = 1;
        for (String id : unlocked) {
            Node node = nodes.get(id);
            if (node != null) {
                n += node.analyzeSlots();
            }
        }
        return n;
    }

    /**
     * {@code nodeId} の前提(親)が {@code unlocked} の内容で満たされているか。
     * {@code anyParent} なら親のどれか1つ、そうでなければ全部が開放済みである必要がある。
     */
    public boolean parentsSatisfied(String nodeId, java.util.Set<String> unlocked) {
        Node n = nodes.get(nodeId);
        if (n == null) {
            return false;
        }
        if (n.parents().isEmpty()) {
            return true;
        }
        if (n.anyParent()) {
            for (String p : n.parents()) {
                if (unlocked.contains(p)) {
                    return true;
                }
            }
            return false;
        }
        for (String p : n.parents()) {
            if (!unlocked.contains(p)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 二者択一グループで既に別のノードを選んでしまっているか。
     *
     * @return 先に開放されている同グループのノード id。競合が無ければ null。
     */
    public String exclusiveConflict(String nodeId, java.util.Set<String> unlocked) {
        Node n = nodes.get(nodeId);
        if (n == null || n.exclusiveGroup().isEmpty()) {
            return null;
        }
        for (Node o : nodes.values()) {
            if (!o.id().equals(nodeId) && o.tier() == n.tier()
                    && n.exclusiveGroup().equals(o.exclusiveGroup()) && unlocked.contains(o.id())) {
                return o.id();
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────── json ⇄ 解決

    static ResearchTable fromJson(Json j) {
        int dp = j != null && j.defaultPoints != null ? j.defaultPoints : 0;
        Map<String, Integer> ip = j != null && j.itemPoints != null
                ? normalizePoints(j.itemPoints) : DEFAULT_ITEM_POINTS;
        Layout layout = parseLayout(j != null ? j.layout : null);
        Map<String, Node> nodes = j != null && j.nodes != null
                ? parseNodes(j.nodes, layout) : DEFAULT_NODES;
        return new ResearchTable(dp, ip, layout, nodes);
    }

    /** ノード枠の下限(これより小さいとアイコン・文字が入らない)。 */
    public static final int MIN_NODE_W = 60;
    public static final int MIN_NODE_H = 24;

    /** 小さすぎるとアイコン・文字が入らない/重なるので下限を掛ける。 */
    private static Layout parseLayout(LayoutJson lj) {
        int w = lj != null && lj.nodeWidth != null ? Math.max(MIN_NODE_W, lj.nodeWidth) : Layout.DEFAULT.nodeWidth();
        int h = lj != null && lj.nodeHeight != null ? Math.max(MIN_NODE_H, lj.nodeHeight) : Layout.DEFAULT.nodeHeight();
        int sx = lj != null && lj.spacingX != null ? Math.max(w + 8, lj.spacingX) : Math.max(Layout.DEFAULT.spacingX(), w + 52);
        int sy = lj != null && lj.spacingY != null ? Math.max(h + 8, lj.spacingY) : Math.max(Layout.DEFAULT.spacingY(), h + 52);
        return new Layout(w, h, sx, sy);
    }

    private static Map<String, Integer> normalizePoints(Map<String, Integer> raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : raw.entrySet()) {
            if (e.getValue() != null) {
                out.put(normalizeId(e.getKey()), e.getValue());
            }
        }
        return out;
    }

    /** @param layout ノードごとの {@code width}/{@code height} が無いときに使う既定サイズ。 */
    private static Map<String, Node> parseNodes(Map<String, NodeJson> raw, Layout layout) {
        Map<String, Node> out = new LinkedHashMap<>();
        for (Map.Entry<String, NodeJson> e : raw.entrySet()) {
            String id = e.getKey();
            if (id.startsWith("_")) {
                continue;
            }
            NodeJson n = e.getValue();
            if (n == null) {
                continue;
            }
            Icon icon = n.icon != null
                    ? new Icon(n.icon.type != null ? n.icon.type : "item", n.icon.id != null ? n.icon.id : "")
                    : new Icon("item", "minecraft:book");
            out.put(id, new Node(
                    id,
                    n.name != null ? n.name : id,
                    n.description != null ? n.description : "",
                    icon,
                    n.cost != null ? Math.max(0, n.cost) : 0,
                    n.tier != null ? Math.max(1, Math.min(6, n.tier)) : 1,
                    n.analyzeSlots != null ? Math.max(0, n.analyzeSlots) : 0,
                    n.x != null ? n.x : 0.0,
                    n.y != null ? n.y : 0.0,
                    n.parents != null ? List.copyOf(n.parents) : List.of(),
                    n.unlocks != null ? List.copyOf(n.unlocks) : List.of(),
                    "any".equalsIgnoreCase(n.parentMode),
                    n.exclusiveGroup != null ? n.exclusiveGroup.trim() : "",
                    cellsToPx(n.widthCells, layout.spacingX(), MIN_NODE_W, layout.nodeWidth()),
                    cellsToPx(n.heightCells, layout.spacingY(), MIN_NODE_H, layout.nodeHeight())));
        }
        return out;
    }

    /**
     * ノード個別の格子単位サイズをピクセルへ。{@code cells} が null なら {@code fallbackPx}(= layout の既定値)。
     *
     * @param cells      格子単位のサイズ(1.0 = 座標点1つぶん = {@code spacing} ピクセル)
     * @param spacing    その軸の格子間隔(ピクセル)
     * @param minPx      枠の下限ピクセル
     * @param fallbackPx 個別指定が無いときの既定ピクセルサイズ
     */
    private static int cellsToPx(Double cells, int spacing, int minPx, int fallbackPx) {
        if (cells == null) {
            return fallbackPx;
        }
        return Math.max(minPx, (int) Math.round(cells * spacing));
    }

    /** 名前空間省略時は {@code overgrown_vegetation}。先頭 {@code #}(タグ)はそのまま。 */
    private static String normalizeId(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        if (s.startsWith("#")) {
            return s;
        }
        return s.contains(":") ? s : OvergrownVegetation.MODID + ":" + s;
    }

    // ───────────────────────────────────────────────── 既定値(バンドル research.json の写経)

    private static final Map<String, Integer> DEFAULT_ITEM_POINTS = buildDefaultItemPoints();
    private static final Map<String, Node> DEFAULT_NODES = buildDefault();

    public static final ResearchTable DEFAULT = new ResearchTable(0, DEFAULT_ITEM_POINTS, Layout.DEFAULT, DEFAULT_NODES);

    private static Map<String, Integer> buildDefaultItemPoints() {
        // mob ドロップのみ(厳密)。石・木材など非ドロップ品は defaultPoints(0)のまま = ポイントにならない。
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String s : new String[]{"plant_fiber", "hardened_bark", "arthropod_limb", "reptile_scute", "fish_scale"}) {
            m.put(OvergrownVegetation.MODID + ":" + s, 1);
        }
        for (String s : new String[]{"overgrown_exoskeleton", "scaturium_exoskeleton", "sail_membrane", "meganeura_wing",
                "protophasma_antenna"}) {
            m.put(OvergrownVegetation.MODID + ":" + s, 2);
        }
        for (String s : new String[]{"phalangiotarbus_fang", "meganeura_compound_eye", "megarachne_pincer"}) {
            m.put(OvergrownVegetation.MODID + ":" + s, 4);
        }
        // 順 1 の着手順 11(2026-10-04、Claude 仮決め): G1 の汚染されたバニラ素材 1 / グループ共通・胞子嚢 2 / 適応の記録 5
        for (String s : new String[]{"contaminated_meat", "rotten_spore_flesh", "contaminated_wool", "contaminated_string"}) {
            m.put(OvergrownVegetation.MODID + ":" + s, 1);
        }
        for (String s : new String[]{"parasitic_mycelium", "arthropod_shell", "predator_tendon", "spore_gland_tissue", "spore_sac"}) {
            m.put(OvergrownVegetation.MODID + ":" + s, 2);
        }
        for (String s : new String[]{"grasp", "shield", "aim", "water", "dark", "devour", "dig"}) {
            m.put(OvergrownVegetation.MODID + ":adaptation_record_" + s, 5);
        }
        // 序盤の作り直し(2026-10-22、仮決め): 地脈の花 2・地脈の結晶の欠片 8。F1 より前から研究を始められる素材。
        m.put(OvergrownVegetation.MODID + ":ley_flower", 2);
        m.put(OvergrownVegetation.MODID + ":ley_crystal_shard", 8);
        m.put(OvergrownVegetation.MODID + ":unknown_sample", 5);
        return m;
    }

    private static Node def(String id, String name, String description, String iconItem, int cost, int tier,
                            double x, double y, List<String> parents, List<String> unlocks) {
        return def(id, name, description, iconItem, cost, tier, 0, x, y, parents, unlocks);
    }

    private static Node def(String id, String name, String description, String iconItem, int cost, int tier,
                            int analyzeSlots, double x, double y, List<String> parents, List<String> unlocks) {
        return new Node(id, name, description, new Icon("item", iconItem), cost, tier, analyzeSlots, x, y,
                parents, unlocks, false, "", Layout.DEFAULT.nodeWidth(), Layout.DEFAULT.nodeHeight());
    }

    private static List<String> armorSet(String set, String... pieces) {
        List<String> l = new ArrayList<>();
        for (String piece : pieces.length > 0 ? pieces : new String[]{"helmet", "chestplate", "leggings", "boots"}) {
            l.add(set + "_" + piece);
        }
        return l;
    }

    private static List<String> toolSet(String set) {
        List<String> l = new ArrayList<>();
        for (String type : new String[]{"axe", "pickaxe", "shovel", "hoe", "sword"}) {
            l.add(set + "_" + type);
        }
        return l;
    }

    private static Map<String, Node> buildDefault() {
        Map<String, Node> m = new LinkedHashMap<>();

        // 地脈技術の 2 段目(2026-10-22、仮のノード。コストは仮。研究ツリーの再編で系統に分ける)。design/research.json と同じ。
        m.put("ley_technology", def("ley_technology", "地脈技術",
                "大地を流れる地脈から取り出した力で、暮らしの機械を動かす。地脈炉・地脈灯・上昇気流機はどれも地脈電池で動く。(仮のノード。研究ツリーの再編で系統に分ける)",
                "overgrown_vegetation:ley_furnace", 10, 1, 0.25, 2.5,
                List.of(), List.of("ley_furnace", "ley_lamp", "ley_updraft", "glider", "ley_meter")));

        // 地脈技術の 4 段目(2026-10-24、仮のノード): 前兆の頃から汲み上げ器の雫に混じる未知の試料を解析すると胞子濃度計が作れる。
        m.put("unknown_sample", def("unknown_sample", "未知の試料",
                "汲み上げ器の雫に混じっていた、見たことのない灰色の粒を調べる。粒は空気の中から降ってきたものらしい。空気に漂う細かな粒を測る計器が作れる。(仮のノード。研究ツリーの再編で動かす)",
                "overgrown_vegetation:unknown_sample", 5, 1, 0.25, 5.0,
                List.of(), List.of("spore_meter")));

        m.put("ley_conversion", def("ley_conversion", "転用",
                "暮らしの地脈の機械に植物側の素材を組み込み、森に向けた機械へ作り替える。地脈灯は忌避の精油で忌避灯に、上昇気流機は胞子の濾過膜で胞子除去装置になる。(仮のノード。研究ツリーの再編で動かす)",
                "overgrown_vegetation:repellent_lamp", 10, 1, 0.25, 3.75,
                List.of("ley_technology"), List.of("repellent_lamp", "spore_purifier", "mortar_repellent_oil", "spore_membrane")));

        m.put("composite_materials", def("composite_materials", "複合素材の加工",
                "繊維・鱗・牙を加工して上位装備の素材にする。解析枠 +1。",
                "overgrown_vegetation:composite_fiber", 8, 2, 1, 0, 1,
                List.of(),
                List.of("composite_fiber", "reptile_leather")));

        m.put("exoskeleton_armor", def("exoskeleton_armor", "スカトゥリウムの外骨格の防具",
                "節足種の外骨格を組み合わせた軽い防具。",
                "overgrown_vegetation:exoskeleton_chestplate", 8, 2, 2, -1,
                // 外骨格はブーツをアイテムごと消してある(モデルにブーツのボーンが無く、見た目が付かないため)。
                List.of(), armorSet("exoskeleton", "helmet", "chestplate", "leggings")));

        m.put("chlorophyllite_armor", def("chlorophyllite_armor", "クロロフィライトの防具",
                "葉緑を宿した鉱石を精錬して鍛えた防具。鉄とダイヤの中間の性能。耐久は鉄と同じ。",
                "overgrown_vegetation:chlorophyllite_chestplate", 15, 3, 2, 0,
                List.of("exoskeleton_armor", "composite_materials"), armorSet("chlorophyllite")));

        m.put("chlorophyllite_tools", def("chlorophyllite_tools", "クロロフィライトの道具",
                "クロロフィライトを鍛えた道具。鉄とダイヤの中間の性能。耐久は鉄と同じ。",
                "overgrown_vegetation:chlorophyllite_pickaxe", 15, 3, 2, 2,
                List.of("composite_materials"), toolSet("chlorophyllite")));

        m.put("reinforced_alloy", def("reinforced_alloy", "強化合金の鋳造",
                "クロロフィライトを歯質・複合繊維質と合金化する。最上位装備の要。解析枠 +1。",
                "overgrown_vegetation:chlorophyllite_alloy", 20, 4, 1, 0, 0,
                List.of("chlorophyllite_armor"),
                List.of("chlorophyllite_alloy", "sap_crystal_softener")));

        m.put("chlorophyllite_alloy_armor", def("chlorophyllite_alloy_armor", "クロロフィライト合金の防具",
                "クロロフィライトを合金化した最上位の防具。",
                "overgrown_vegetation:chlorophyllite_alloy_chestplate", 30, 5, 0, 1,
                List.of("reinforced_alloy"), armorSet("chlorophyllite_alloy")));

        m.put("chlorophyllite_alloy_tools", def("chlorophyllite_alloy_tools", "クロロフィライト合金の道具",
                "合金を鍛えた最上位の道具。性能はダイヤ並みだが、耐久は鉄より少し上どまり。",
                "overgrown_vegetation:chlorophyllite_alloy_pickaxe", 30, 6, 0, 0,
                List.of("reinforced_alloy", "chlorophyllite_tools"), toolSet("chlorophyllite_alloy")));

        return m;
    }
}
