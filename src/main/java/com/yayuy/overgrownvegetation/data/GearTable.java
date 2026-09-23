package com.yayuy.overgrownvegetation.data;

import net.minecraft.world.item.ArmorItem;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code gear.json} — MOD の装備・ツールの「性能」設計値。
 *
 * <p>Mob ステータスと同じ思想で、防御力・靭性・耐久・採掘速度・攻撃力ボーナス・エンチャント適性の数値を
 * Java のハードコードではなく {@code config/overgrown_vegetation/gear.json} から読む。既定値
 * ({@link #DEFAULT})は下の {@code buildDefault*()} に写経してあり、json 欠損時のフォールバックになる。
 *
 * <p>{@link com.yayuy.overgrownvegetation.registry.ModArmorMaterials} /
 * {@link com.yayuy.overgrownvegetation.registry.ModTiers} が {@code register} 時にここを参照する。
 * これらはアイテム登録時に一度だけ読まれるので、{@code /ov design reload} では
 * 再反映されず、変更の反映には再起動が必要(捕食表・フェーズと違う点)。
 *
 * <p>キー(=装備セット/ツールセットの識別子)は {@code overgrown_vegetation} 名前空間内のパス:
 * <ul>
  *   <li>防具: {@code exoskeleton} / {@code chlorophyllite} / {@code chlorophyllite_alloy}</li>
 *   <li>ツール: {@code chlorophyllite} / {@code chlorophyllite_alloy}</li>
 * </ul>
 *
 * <p>防具は部位({@code helmet}/{@code chestplate}/{@code leggings}/{@code boots})ごとに耐久を、
 * ツールは種別({@code axe}/{@code pickaxe}/{@code shovel}/{@code hoe}/{@code sword})ごとに
 * 耐久・攻撃力・攻撃速度を個別に上書きできる(未指定はセット共通値へフォールバック)。
 *
 * @see DesignData
 */
public final class GearTable {

    public static final String[] ARMOR_PIECES = {"helmet", "chestplate", "leggings", "boots"};
    public static final String[] TOOL_TYPES = {"axe", "pickaxe", "shovel", "hoe", "sword"};

    // ─────────────────────────────────────────────────────────────── DTO(Gson)

    public static final class Json {
        public Map<String, ArmorJson> armor;
        public Map<String, ToolJson> tools;
    }

    /** 1 防具セットの生値。{@code null} フィールドは既定値へフォールバックする。 */
    public static final class ArmorJson {
        public Integer helmet;
        public Integer chestplate;
        public Integer leggings;
        public Integer boots;
        /** 靭性(ダイヤ=2.0、ネザライト=3.0)。 */
        public Double toughness;
        /** ノックバック耐性(ネザライト=0.1)。 */
        public Double knockbackResistance;
        /** エンチャント適性(鉄=9、ダイヤ=10)。 */
        public Integer enchantmentValue;
        /** 耐久係数。実耐久 = 部位ごとの基準値 × これ(革=5、鉄=15、ダイヤ=33)。 */
        public Integer durabilityFactor;
        /** 部位ごとの絶対耐久の上書き(任意)。キーは {@link #ARMOR_PIECES}。指定した部位は係数計算より優先。 */
        public Map<String, Integer> durability;
    }

    /** 1 ツールセットの生値。{@code null} フィールドは既定値へフォールバックする。 */
    public static final class ToolJson {
        /** セット共通の耐久(木=59、石=131、鉄=250、ダイヤ=1561)。種別ごとに {@code perTool} で上書き可。 */
        public Integer uses;
        /** 採掘速度(木=2.0、石=4.0、鉄=6.0、ダイヤ=8.0)。セット共通。 */
        public Double speed;
        /** 攻撃力ボーナス(木/金=0、石=1、鉄=2、ダイヤ=3、ネザライト=4)。セット共通。 */
        public Double attackDamageBonus;
        /** エンチャント適性(木=15、石=5、鉄=14、ダイヤ=10)。セット共通。 */
        public Integer enchantmentValue;
        /** 種別ごとの上書き(任意)。キーは {@link #TOOL_TYPES}。 */
        public Map<String, PerToolJson> perTool;
    }

    /** ツール種別ごとの上書き値。{@code null} は種別既定へフォールバック。 */
    public static final class PerToolJson {
        /** 絶対耐久。未指定は {@code uses}(盾は {@code uses × 2})。 */
        public Integer durability;
        /** 基準攻撃力(最終値 = attackDamageBonus + これ)。未指定はバニラ準拠の種別既定。 */
        public Double attackDamage;
        /** 攻撃速度(負値ほど遅い)。未指定はバニラ準拠の種別既定。 */
        public Double attackSpeed;
    }

    // ─────────────────────────────────────────────────────────── 解決済みの値

    public static final class ArmorSpec {
        private final int helmet;
        private final int chestplate;
        private final int leggings;
        private final int boots;
        private final float toughness;
        private final float knockbackResistance;
        private final int enchantmentValue;
        private final int durabilityFactor;
        private final Map<String, Integer> durabilityOverride;

        private ArmorSpec(int helmet, int chestplate, int leggings, int boots,
                          float toughness, float knockbackResistance,
                          int enchantmentValue, int durabilityFactor,
                          Map<String, Integer> durabilityOverride) {
            this.helmet = helmet;
            this.chestplate = chestplate;
            this.leggings = leggings;
            this.boots = boots;
            this.toughness = toughness;
            this.knockbackResistance = knockbackResistance;
            this.enchantmentValue = enchantmentValue;
            this.durabilityFactor = durabilityFactor;
            this.durabilityOverride = durabilityOverride;
        }

        public int defense(ArmorItem.Type type) {
            return switch (type) {
                case HELMET -> helmet;
                case CHESTPLATE, BODY -> chestplate;
                case LEGGINGS -> leggings;
                case BOOTS -> boots;
            };
        }

        public float toughness() {
            return toughness;
        }

        public float knockbackResistance() {
            return knockbackResistance;
        }

        public int enchantmentValue() {
            return enchantmentValue;
        }

        public int durabilityFactor() {
            return durabilityFactor;
        }

        /** 部位の実耐久。json で絶対値が指定されていればそれ、無ければ {@code 部位基準値 × 係数}。 */
        public int durability(ArmorItem.Type type) {
            Integer o = durabilityOverride.get(pieceName(type));
            return o != null ? o : type.getDurability(durabilityFactor);
        }

        private static String pieceName(ArmorItem.Type type) {
            return switch (type) {
                case HELMET -> "helmet";
                case CHESTPLATE, BODY -> "chestplate";
                case LEGGINGS -> "leggings";
                case BOOTS -> "boots";
            };
        }
    }

    public static final class ToolSpec {
        private final int uses;
        private final float speed;
        private final float attackDamageBonus;
        private final int enchantmentValue;
        private final Map<String, PerToolJson> perTool;

        private ToolSpec(int uses, float speed, float attackDamageBonus, int enchantmentValue,
                         Map<String, PerToolJson> perTool) {
            this.uses = uses;
            this.speed = speed;
            this.attackDamageBonus = attackDamageBonus;
            this.enchantmentValue = enchantmentValue;
            this.perTool = perTool;
        }

        public int uses() {
            return uses;
        }

        public float speed() {
            return speed;
        }

        public float attackDamageBonus() {
            return attackDamageBonus;
        }

        public int enchantmentValue() {
            return enchantmentValue;
        }

        /** 種別の実耐久。未指定なら {@code uses}。 */
        public int durability(String toolType) {
            PerToolJson pt = perTool.get(toolType);
            if (pt != null && pt.durability != null) {
                return pt.durability;
            }
            return uses;
        }

        /** 種別の基準攻撃力(攻撃力ボーナス加算前)。 */
        public float attackDamage(String toolType) {
            PerToolJson pt = perTool.get(toolType);
            if (pt != null && pt.attackDamage != null) {
                return pt.attackDamage.floatValue();
            }
            return DEFAULT_TOOL_DAMAGE.getOrDefault(toolType, 0f);
        }

        /** 種別の攻撃速度。 */
        public float attackSpeed(String toolType) {
            PerToolJson pt = perTool.get(toolType);
            if (pt != null && pt.attackSpeed != null) {
                return pt.attackSpeed.floatValue();
            }
            return DEFAULT_TOOL_SPEED.getOrDefault(toolType, 0f);
        }
    }

    // 現行 ModItems に固定されていたバニラ準拠の種別ごとの基準攻撃力 / 攻撃速度(json 未指定時のフォールバック)。
    private static final Map<String, Float> DEFAULT_TOOL_DAMAGE = Map.of(
            "axe", 6.0F, "pickaxe", 1.0F, "shovel", 1.5F, "hoe", -2.0F, "sword", 3.0F);
    private static final Map<String, Float> DEFAULT_TOOL_SPEED = Map.of(
            "axe", -3.1F, "pickaxe", -2.8F, "shovel", -3.0F, "hoe", -1.0F, "sword", -2.4F);

    // ─────────────────────────────────────────────────────────────────── 本体

    private final Map<String, ArmorSpec> armor;
    private final Map<String, ToolSpec> tools;

    private GearTable(Map<String, ArmorSpec> armor, Map<String, ToolSpec> tools) {
        this.armor = armor;
        this.tools = tools;
    }

    /** 未知のキーでも安全に汎用の既定値を返す(null にはならない)。 */
    public ArmorSpec armor(String key) {
        ArmorSpec s = armor.get(key);
        return s != null ? s : GENERIC_ARMOR;
    }

    public ToolSpec tool(String key) {
        ToolSpec s = tools.get(key);
        return s != null ? s : GENERIC_TOOL;
    }

    static GearTable fromJson(Json j) {
        Map<String, ArmorSpec> a = new LinkedHashMap<>();
        Map<String, ToolSpec> t = new LinkedHashMap<>();
        for (Map.Entry<String, ArmorJson> e : DEFAULT_ARMOR_JSON.entrySet()) {
            ArmorJson over = j != null && j.armor != null ? j.armor.get(e.getKey()) : null;
            a.put(e.getKey(), resolveArmor(e.getValue(), over));
        }
        for (Map.Entry<String, ToolJson> e : DEFAULT_TOOL_JSON.entrySet()) {
            ToolJson over = j != null && j.tools != null ? j.tools.get(e.getKey()) : null;
            t.put(e.getKey(), resolveTool(e.getValue(), over));
        }
        // json 側にしか無いキー(ユーザーが増やした)も拾う。
        if (j != null && j.armor != null) {
            for (Map.Entry<String, ArmorJson> e : j.armor.entrySet()) {
                a.putIfAbsent(e.getKey(), resolveArmor(DEFAULT_ARMOR_JSON.get("exoskeleton"), e.getValue()));
            }
        }
        if (j != null && j.tools != null) {
            for (Map.Entry<String, ToolJson> e : j.tools.entrySet()) {
                t.putIfAbsent(e.getKey(), resolveTool(DEFAULT_TOOL_JSON.get("chlorophyllite"), e.getValue()));
            }
        }
        return new GearTable(a, t);
    }

    private static ArmorSpec resolveArmor(ArmorJson def, ArmorJson over) {
        Map<String, Integer> dur = new LinkedHashMap<>();
        if (def.durability != null) {
            dur.putAll(def.durability);
        }
        if (over != null && over.durability != null) {
            for (Map.Entry<String, Integer> e : over.durability.entrySet()) {
                if (e.getValue() != null) {
                    dur.put(e.getKey(), e.getValue());
                }
            }
        }
        return new ArmorSpec(
                pick(over != null ? over.helmet : null, def.helmet),
                pick(over != null ? over.chestplate : null, def.chestplate),
                pick(over != null ? over.leggings : null, def.leggings),
                pick(over != null ? over.boots : null, def.boots),
                (float) pick(over != null ? over.toughness : null, def.toughness),
                (float) pick(over != null ? over.knockbackResistance : null, def.knockbackResistance),
                pick(over != null ? over.enchantmentValue : null, def.enchantmentValue),
                pick(over != null ? over.durabilityFactor : null, def.durabilityFactor),
                dur);
    }

    private static ToolSpec resolveTool(ToolJson def, ToolJson over) {
        Map<String, PerToolJson> pt = new LinkedHashMap<>();
        if (def.perTool != null) {
            pt.putAll(def.perTool);
        }
        if (over != null && over.perTool != null) {
            for (Map.Entry<String, PerToolJson> e : over.perTool.entrySet()) {
                pt.merge(e.getKey(), e.getValue(), GearTable::mergePerTool);
            }
        }
        return new ToolSpec(
                pick(over != null ? over.uses : null, def.uses),
                (float) pick(over != null ? over.speed : null, def.speed),
                (float) pick(over != null ? over.attackDamageBonus : null, def.attackDamageBonus),
                pick(over != null ? over.enchantmentValue : null, def.enchantmentValue),
                pt);
    }

    private static PerToolJson mergePerTool(PerToolJson base, PerToolJson over) {
        if (over == null) {
            return base;
        }
        PerToolJson r = new PerToolJson();
        r.durability = over.durability != null ? over.durability : (base != null ? base.durability : null);
        r.attackDamage = over.attackDamage != null ? over.attackDamage : (base != null ? base.attackDamage : null);
        r.attackSpeed = over.attackSpeed != null ? over.attackSpeed : (base != null ? base.attackSpeed : null);
        return r;
    }

    private static int pick(Integer a, int b) {
        return a != null ? a : b;
    }

    private static double pick(Double a, double b) {
        return a != null ? a : b;
    }

    // ───────────────────────────────────────────────── 既定値(現行バランス案)

    private static final Map<String, ArmorJson> DEFAULT_ARMOR_JSON = buildDefaultArmor();
    private static final Map<String, ToolJson> DEFAULT_TOOL_JSON = buildDefaultTools();

    public static final GearTable DEFAULT = fromJson(null);

    private static final ArmorSpec GENERIC_ARMOR =
            resolveArmor(DEFAULT_ARMOR_JSON.get("exoskeleton"), null);
    private static final ToolSpec GENERIC_TOOL =
            resolveTool(DEFAULT_TOOL_JSON.get("chlorophyllite"), null);

    private static ArmorJson armorJson(int helmet, int chest, int legs, int boots,
                                       double toughness, double kbRes, int ench, int durFactor) {
        ArmorJson a = new ArmorJson();
        a.helmet = helmet;
        a.chestplate = chest;
        a.leggings = legs;
        a.boots = boots;
        a.toughness = toughness;
        a.knockbackResistance = kbRes;
        a.enchantmentValue = ench;
        a.durabilityFactor = durFactor;
        return a;
    }

    private static ToolJson toolJson(int uses, double speed, double attackDamageBonus, int ench) {
        ToolJson t = new ToolJson();
        t.uses = uses;
        t.speed = speed;
        t.attackDamageBonus = attackDamageBonus;
        t.enchantmentValue = ench;
        return t;
    }

    private static Map<String, ArmorJson> buildDefaultArmor() {
        Map<String, ArmorJson> m = new LinkedHashMap<>();
        //                              head chest legs boots  tough  kbRes ench durF
        m.put("exoskeleton",              armorJson(2, 5, 4, 1, 0.0D, 0.0D, 12, 13));
        m.put("chlorophyllite",           armorJson(2, 7, 6, 2, 1.0D, 0.0D, 10, 15));
        m.put("chlorophyllite_alloy",     armorJson(3, 8, 6, 3, 2.0D, 0.0D, 10, 18));
        // ネザライト(3/8/6/3、靭性3、KB0.1、耐久係数37)の 1.5 倍。0.5 は兜を上げて合計 30 に合わせている。
        m.put("reinforced_chlorophyllite_alloy", armorJson(5, 12, 9, 4, 4.5D, 0.15D, 15, 56));
        return m;
    }

    private static Map<String, ToolJson> buildDefaultTools() {
        Map<String, ToolJson> m = new LinkedHashMap<>();
        //                             uses  speed  dmgBonus ench
        m.put("chlorophyllite",       toolJson(250, 7.0D, 2.5D, 12));
        m.put("chlorophyllite_alloy", toolJson(320, 8.0D, 3.0D, 10));
        return m;
    }
}
