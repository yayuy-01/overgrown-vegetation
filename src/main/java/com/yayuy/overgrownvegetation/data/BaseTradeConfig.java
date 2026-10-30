package com.yayuy.overgrownvegetation.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * 同盟軍の信頼・寄付・取引の数値(順 2 の着手順 5、2026-10-07)。{@code defense.json} の {@code alliedBase.trade}。
 *
 * <p><b>ユーザー指定(10-07)</b>: 寄付は値打ちの表に載っている物なら何でも受け取り、値打ちはその基地の貯蓄に入り、信頼は値打ちに比例して上がる
 * (1 日に上がる量には上限)/ 取引の通貨は軍票 / 寄付は信頼 + お礼の軍票(売るより少ない)、素材を売ると軍票だけ(寄付より多い)/
 * 軍票で買うと買った品の値打ちの分だけ基地の貯蓄が減り、足りないと買えない / 取引は何度か寄付して信頼が上がってから /
 * 信頼は同盟軍全体で 1 人に 1 つ。<b>数値はどれも Claude 仮決め</b>(値打ちの単位は貯蓄と同じ = 鉄インゴット 1 個が 2)。
 */
public final class BaseTradeConfig {

    /** {@code alliedBase.trade} の json。書いていない項目は既定値。 */
    public static final class Json {
        /** 値打ち 1 あたりに上がる信頼(0.05 = 値打ち 20 で 1)。 */
        public Double trustPerValue;
        /** 暦の 1 日に上がる信頼の上限。 */
        public Double trustDailyCap;
        /** 信頼の上限。 */
        public Double trustMax;
        /** 取引(売り買い)ができる信頼。 */
        public Integer trustTrade;
        /** 品が増える信頼。 */
        public Integer trustGoods;
        /** 護衛・援軍ができる信頼。護衛・援軍は 1.0.0 の後に回した(ユーザー 10-07)ので、今はどこからも見ない。 */
        public Integer trustEscort;
        public Integer trustReinforce;
        /** 信頼 → 買うときの割引(その信頼以上で効く)。 */
        public Map<String, Double> discounts;
        /** 売るとき、値打ちいくつで軍票 1 枚か。 */
        public Integer scripValue;
        /** 寄付のお礼の軍票 = 売ったときの軍票 × これ。 */
        public Double donationThanks;
        /** 買うときの値段 = 品の値打ち × これ ÷ {@link #scripValue}(切り上げ)。 */
        public Double buyMarkup;
        /** 表に無い食べ物の値打ち = 満腹度 × これ。 */
        public Double foodValuePerNutrition;
        /** 研究ポイントのある物(mob のドロップ)の値打ち = ポイント × これ。 */
        public Double researchPointValue;
        /** 値打ちの表(アイテム id → 1 個の値打ち)。ここに書いたものは既定の表に重ねる(0 で受け取らない)。 */
        public Map<String, Double> values;
        /** 受け取らない物(アイテム id)。 */
        public List<String> deny;
        /** 取引の品。書くと既定の品をまるごと置き換える。 */
        public List<OfferJson> offers;
    }

    public static final class OfferJson {
        public String item;
        public Integer count;
        /** 品の値打ち(買うと基地の貯蓄がこれだけ減る)。書かなければ値打ちの表 × 数。 */
        public Integer cost;
        public Integer minTrust;
        /** 売る基地の種類の id(書かなければどの基地でも)。 */
        public List<String> types;
        /** ポーションの中身(minecraft:healing など)。 */
        public String potion;
    }

    /**
     * 取引の品 1 つ。{@code cost} が負なら値打ちの表 × 数で決める。{@code types} が空ならどの基地でも売る。
     */
    public record Offer(String item, int count, int cost, int minTrust, List<String> types, String potion) {
        public boolean soldAt(String typeId) {
            return types.isEmpty() || types.contains(typeId);
        }
    }

    public final double trustPerValue;
    public final double trustDailyCap;
    public final double trustMax;
    public final int trustTrade;
    public final int trustGoods;
    public final int trustEscort;
    public final int trustReinforce;
    public final NavigableMap<Integer, Double> discounts;
    public final int scripValue;
    public final double donationThanks;
    public final double buyMarkup;
    public final double foodValuePerNutrition;
    public final double researchPointValue;
    public final Map<String, Double> values;
    public final Set<String> deny;
    public final List<Offer> offers;

    private BaseTradeConfig(Json j) {
        this.trustPerValue = j.trustPerValue != null ? Math.max(0.0D, j.trustPerValue) : 0.05D;
        this.trustDailyCap = j.trustDailyCap != null ? Math.max(0.0D, j.trustDailyCap) : 5.0D;
        this.trustMax = j.trustMax != null ? Math.max(1.0D, j.trustMax) : 100.0D;
        this.trustTrade = j.trustTrade != null ? Math.max(0, j.trustTrade) : 10;
        this.trustGoods = j.trustGoods != null ? Math.max(0, j.trustGoods) : 25;
        this.trustEscort = j.trustEscort != null ? Math.max(0, j.trustEscort) : 40;
        this.trustReinforce = j.trustReinforce != null ? Math.max(0, j.trustReinforce) : 60;
        NavigableMap<Integer, Double> d = new TreeMap<>();
        Map<String, Double> givenDiscounts = j.discounts != null ? j.discounts : Map.of("25", 0.1D, "60", 0.2D);
        for (Map.Entry<String, Double> e : givenDiscounts.entrySet()) {
            try {
                d.put(Integer.parseInt(e.getKey().trim()), Math.max(0.0D, Math.min(0.9D, e.getValue() != null ? e.getValue() : 0.0D)));
            } catch (NumberFormatException ignored) {
                // 数で書いていない段は飛ばす
            }
        }
        this.discounts = Collections.unmodifiableNavigableMap(d);
        this.scripValue = j.scripValue != null ? Math.max(1, j.scripValue) : 10;
        this.donationThanks = j.donationThanks != null ? Math.max(0.0D, Math.min(1.0D, j.donationThanks)) : 0.25D;
        this.buyMarkup = j.buyMarkup != null ? Math.max(1.0D, j.buyMarkup) : 1.5D;
        this.foodValuePerNutrition = j.foodValuePerNutrition != null ? Math.max(0.0D, j.foodValuePerNutrition) : 0.25D;
        this.researchPointValue = j.researchPointValue != null ? Math.max(0.0D, j.researchPointValue) : 2.0D;
        Map<String, Double> v = new LinkedHashMap<>(DEFAULT_VALUES);
        if (j.values != null) {
            for (Map.Entry<String, Double> e : j.values.entrySet()) {
                v.put(id(e.getKey()), e.getValue() != null ? Math.max(0.0D, e.getValue()) : 0.0D);
            }
        }
        this.values = Collections.unmodifiableMap(v);
        Set<String> deny = new LinkedHashSet<>();
        if (j.deny != null) {
            for (String s : j.deny) {
                if (s != null && !s.isBlank()) {
                    deny.add(id(s));
                }
            }
        }
        this.deny = Collections.unmodifiableSet(deny);
        List<Offer> offers = new ArrayList<>();
        if (j.offers != null) {
            for (OfferJson o : j.offers) {
                if (o == null || o.item == null || o.item.isBlank()) {
                    continue;
                }
                offers.add(new Offer(id(o.item), o.count != null ? Math.max(1, Math.min(64, o.count)) : 1,
                        o.cost != null ? Math.max(0, o.cost) : -1, o.minTrust != null ? Math.max(0, o.minTrust) : this.trustTrade,
                        o.types != null ? List.copyOf(o.types) : List.of(), o.potion != null && !o.potion.isBlank() ? id(o.potion) : null));
            }
        } else {
            offers.addAll(defaultOffers(this.trustTrade, this.trustGoods));
        }
        this.offers = List.copyOf(offers);
    }

    public static BaseTradeConfig of(Json json) {
        return new BaseTradeConfig(json != null ? json : new Json());
    }

    /** その信頼での買うときの割引(0〜0.9)。 */
    public double discount(double trust) {
        Map.Entry<Integer, Double> e = discounts.floorEntry((int) Math.floor(trust + 1e-9));
        return e != null ? e.getValue() : 0.0D;
    }

    /** 値打ち {@code cost} の品の、信頼 {@code trust} での値段(軍票。1 枚以上)。 */
    public int price(int cost, double trust) {
        double scrip = cost * buyMarkup * (1.0D - discount(trust)) / scripValue;
        return Math.max(1, (int) Math.ceil(scrip - 1e-9));
    }

    private static String id(String s) {
        String t = s.trim();
        return t.contains(":") ? t : "minecraft:" + t;
    }

    // ───────────────────────────────────────────── 既定値(Claude 仮決め)

    /** 値打ちの表の既定値。単位は貯蓄と同じ(鉄インゴット 1 個 = 2。整備の値段「弾 16 発 = 鉄 1 + 火薬 1 で 4」に揃えた)。 */
    private static final Map<String, Double> DEFAULT_VALUES = buildDefaultValues();

    private static Map<String, Double> buildDefaultValues() {
        Map<String, Double> m = new LinkedHashMap<>();
        java.util.function.BiConsumer<String, Double> mc = (k, v) -> m.put("minecraft:" + k, v);
        java.util.function.BiConsumer<String, Double> ov = (k, v) -> m.put("overgrown_vegetation:" + k, v);
        // 金属・鉱物
        mc.accept("iron_nugget", 0.2D);
        mc.accept("iron_ingot", 2.0D);
        mc.accept("iron_block", 18.0D);
        mc.accept("raw_iron", 1.5D);
        mc.accept("copper_ingot", 0.5D);
        mc.accept("copper_block", 4.5D);
        mc.accept("raw_copper", 0.3D);
        mc.accept("gold_nugget", 0.4D);
        mc.accept("gold_ingot", 4.0D);
        mc.accept("gold_block", 36.0D);
        mc.accept("raw_gold", 3.0D);
        mc.accept("diamond", 20.0D);
        mc.accept("diamond_block", 180.0D);
        mc.accept("emerald", 8.0D);
        mc.accept("emerald_block", 72.0D);
        mc.accept("netherite_scrap", 40.0D);
        mc.accept("netherite_ingot", 170.0D);
        mc.accept("coal", 1.0D);
        mc.accept("coal_block", 9.0D);
        mc.accept("charcoal", 1.0D);
        mc.accept("redstone", 2.0D);
        mc.accept("redstone_block", 18.0D);
        mc.accept("lapis_lazuli", 1.0D);
        mc.accept("lapis_block", 9.0D);
        mc.accept("quartz", 1.0D);
        mc.accept("gunpowder", 2.0D);
        // 物資
        mc.accept("leather", 1.0D);
        mc.accept("paper", 0.5D);
        mc.accept("string", 0.5D);
        mc.accept("golden_apple", 34.0D);
        mc.accept("golden_carrot", 4.0D);
        // この mod の建材・弾・設備の消耗品
        ov.accept("autocannon_round", 0.25D);
        ov.accept("repair_material", 2.0D);
        ov.accept("spore_filter", 8.0D);
        ov.accept("reinforced_concrete", 1.0D);
        ov.accept("reinforced_concrete_slab", 0.5D);
        ov.accept("reinforced_concrete_stairs", 1.0D);
        ov.accept("reinforced_concrete_wall", 1.0D);
        ov.accept("sandbags", 1.0D);
        ov.accept("steel_plate", 4.0D);
        ov.accept("asphalt", 1.0D);
        ov.accept("asphalt_slab", 0.5D);
        ov.accept("asphalt_stairs", 1.0D);
        ov.accept("barbed_wire", 1.0D);
        ov.accept("chain_link_fence", 0.5D);
        ov.accept("protective_mask", 12.0D);
        ov.accept("jamming_lamp", 16.0D);
        ov.accept("floodlight", 8.0D);
        // この mod の鉱石・合金(森の素材から作るもの)
        ov.accept("raw_chlorophyllite", 3.0D);
        ov.accept("chlorophyllite", 4.0D);
        ov.accept("chlorophyllite_block", 36.0D);
        ov.accept("chlorophyllite_alloy", 12.0D);
        ov.accept("chlorophyllite_alloy_block", 108.0D);
        ov.accept("raw_hardened_sap_crystal", 2.0D);
        ov.accept("sap_crystal", 3.0D);
        ov.accept("sap_crystal_block", 27.0D);
        ov.accept("raw_phloemite", 3.0D);
        ov.accept("phloemite", 4.0D);
        ov.accept("raw_thylakite", 3.0D);
        ov.accept("thylakite", 4.0D);
        ov.accept("phloemite_alloy_base", 8.0D);
        ov.accept("sunlight_alloy", 16.0D);
        return Collections.unmodifiableMap(m);
    }

    /** 取引の品の既定値(信頼 10 = 基本の品、信頼 25 = 増える品。野戦病院は回復アイテムを足す = ユーザー指定 10-01「回復アイテムを買える」)。 */
    private static List<Offer> defaultOffers(int trade, int goods) {
        List<Offer> o = new ArrayList<>();
        List<String> any = List.of();
        List<String> hospital = List.of("hospital");
        o.add(new Offer("overgrown_vegetation:autocannon_round", 64, -1, trade, any, null));
        o.add(new Offer("overgrown_vegetation:repair_material", 16, -1, trade, any, null));
        o.add(new Offer("overgrown_vegetation:spore_filter", 1, -1, trade, any, null));
        o.add(new Offer("overgrown_vegetation:reinforced_concrete", 16, -1, trade, any, null));
        o.add(new Offer("overgrown_vegetation:sandbags", 16, -1, trade, any, null));
        o.add(new Offer("overgrown_vegetation:barbed_wire", 16, -1, trade, any, null));
        o.add(new Offer("minecraft:bread", 16, -1, trade, any, null));
        o.add(new Offer("minecraft:potion", 1, 8, trade, hospital, "minecraft:healing"));
        o.add(new Offer("minecraft:potion", 1, 10, trade, hospital, "minecraft:regeneration"));
        o.add(new Offer("minecraft:golden_apple", 1, -1, trade, hospital, null));
        o.add(new Offer("overgrown_vegetation:protective_mask", 1, -1, goods, any, null));
        o.add(new Offer("overgrown_vegetation:jamming_lamp", 1, -1, goods, any, null));
        o.add(new Offer("overgrown_vegetation:floodlight", 1, -1, goods, any, null));
        o.add(new Offer("overgrown_vegetation:steel_plate", 16, -1, goods, any, null));
        o.add(new Offer("overgrown_vegetation:asphalt", 32, -1, goods, any, null));
        o.add(new Offer("overgrown_vegetation:chain_link_fence", 16, -1, goods, any, null));
        o.add(new Offer("minecraft:redstone", 16, -1, goods, any, null));
        o.add(new Offer("minecraft:cooked_beef", 16, -1, goods, any, null));
        o.add(new Offer("minecraft:potion", 1, 16, goods, hospital, "minecraft:strong_healing"));
        o.add(new Offer("minecraft:potion", 1, 20, goods, hospital, "minecraft:strong_regeneration"));
        return o;
    }

    /** 既定値だけの設定。{@link #DEFAULT_VALUES} より後に置く(static の初期化は書いた順)。 */
    public static final BaseTradeConfig DEFAULT = of(null);
}
